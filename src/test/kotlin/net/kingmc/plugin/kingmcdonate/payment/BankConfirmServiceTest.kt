package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.payment.bank.BankConfirmService
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation
import net.kingmc.plugin.kingmcdonate.provider.bank.UnmatchedTransfer
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class BankConfirmServiceTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var bank: BankPaymentDao
    private lateinit var totals: PlayerTotalsDao
    private lateinit var fakeCurrency: FakeCurrencyProvider
    private lateinit var enqueued: MutableList<UUID>
    private lateinit var cleared: MutableList<UUID>
    private lateinit var service: BankConfirmService

    private val config = config(pointRate = 1.0)

    private fun config(pointRate: Double, serverId: String = "node-a"): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString("server-id: \"$serverId\"\nbank:\n  point-rate: $pointRate\n")
        return PluginConfig(yaml)
    }

    private fun buildService(currencyAvailable: Boolean = true, serverId: String = "node-a"): BankConfirmService =
        buildServiceWithPromo(net.kingmc.plugin.kingmcdonate.promo.PromoConfig(emptyList()), currencyAvailable, serverId)

    private fun buildServiceWithPromo(
        promoCfg: net.kingmc.plugin.kingmcdonate.promo.PromoConfig,
        currencyAvailable: Boolean = true,
        serverId: String = "node-a",
    ): BankConfirmService {
        fakeCurrency = FakeCurrencyProvider(available = currencyAvailable)
        val currency = CurrencyRegistry(logger) { fakeCurrency }.apply { load(config.currency) }
        enqueued = mutableListOf()
        cleared = mutableListOf()
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) { enqueued.add(playerUuid) }
        }
        val promo = net.kingmc.plugin.kingmcdonate.promo.PromoService { promoCfg }
        val success = DonationSuccessService(
            rewardSink = sink,
            playerDao = PlayerDao(database),
            logger = logger,
            config = { config(1.0, serverId) },
            broadcaster = {},
        )
        return BankConfirmService(
            database, bank, ProcessedBankTxDao(database), totals, PlayerDao(database),
            currency, promo, success, clearQr = { cleared.add(it) }, logger = logger,
            config = { config(1.0, serverId) },
        )
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply {
            connect()
            migrate()
        }
        bank = BankPaymentDao(database)
        totals = PlayerTotalsDao(database)
        service = buildService()
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun newOrder(uuid: UUID = UUID.randomUUID(), amount: Long = 50_000, owner: String = "node-a"): Pair<UUID, String> {
        PlayerDao(database).upsert(uuid, "Alice")
        return uuid to bank.insertPending(uuid, amount, "sepay", owner, 1_000)
    }

    private fun totalAll(uuid: UUID): Long = database.withConnection { conn ->
        conn.prepareStatement(
            "SELECT amount_vnd FROM player_totals WHERE player_uuid = ? AND period = 'ALL' AND method = 'bank'",
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    @Test
    fun `same transaction confirmed twice credits once and adds totals once`() {
        val (uuid, ref) = newOrder()
        service.confirm(BankConfirmation(ref, "TX1", 50_000))
        service.confirm(BankConfirmation(ref, "TX1", 50_000))
        assertEquals(50L, fakeCurrency.balance(uuid)) // 50_000/1000 * 1.0
        assertEquals(50_000L, totalAll(uuid))
        assertEquals(1, enqueued.size)
    }

    @Test
    fun `amount mismatch leaves the order pending and grants nothing`() {
        val (uuid, ref) = newOrder(amount = 50_000)
        service.confirm(BankConfirmation(ref, "TX1", 20_000))
        assertEquals(0L, fakeCurrency.balance(uuid))
        assertEquals(PaymentStatus.PENDING, bank.findByReference(ref)!!.status)
    }

    @Test
    fun `currency unavailable leaves the order pending`() {
        service = buildService(currencyAvailable = false)
        val (uuid, ref) = newOrder()
        service.confirm(BankConfirmation(ref, "TX1", 50_000))
        assertEquals(PaymentStatus.PENDING, bank.findByReference(ref)!!.status)
        assertEquals(0L, fakeCurrency.balance(uuid))
    }

    private fun processedTxCount(ref: String): Int = database.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM processed_bank_tx WHERE reference_code = ?").use { ps ->
            ps.setString(1, ref)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    @Test
    fun `a second transfer with a new tx for a success order is recorded and not re-credited`() {
        val (uuid, ref) = newOrder()
        service.confirm(BankConfirmation(ref, "TX1", 50_000))
        assertEquals(50L, fakeCurrency.balance(uuid))
        // A genuine second transfer (different tx id) for the same already-SUCCESS order.
        service.confirm(BankConfirmation(ref, "TX2", 50_000))
        assertEquals(50L, fakeCurrency.balance(uuid)) // not re-credited
        assertEquals(2, processedTxCount(ref)) // both tx ids recorded for manual reconciliation
        // Replaying the original tx records nothing new.
        service.confirm(BankConfirmation(ref, "TX1", 50_000))
        assertEquals(2, processedTxCount(ref))
    }

    @Test
    fun `promo is computed from the order's creation time, not the confirmation time`() {
        // newOrder creates the order at t=1000; the promo ran [0, 2000] and has long since ended by now.
        // Confirm-time promo would credit the base 50; creation-time promo still grants the bonus.
        val promoCfg = net.kingmc.plugin.kingmcdonate.promo.PromoConfig(
            listOf(net.kingmc.plugin.kingmcdonate.promo.PromoConfig.Promo("x", 100.0, 0, 2_000)),
        )
        service = buildServiceWithPromo(promoCfg)
        val (uuid, ref) = newOrder(amount = 50_000) // base 50 points at rate 1.0, created at t=1000
        service.confirm(BankConfirmation(ref, "TXP", 50_000))
        assertEquals(100L, fakeCurrency.balance(uuid)) // 50 * (1 + 100/100), promo active at creation
        assertEquals(50_000L, totalAll(uuid))          // amount_vnd is face, unaffected by promo
        assertEquals(100L, bank.findByReference(ref)!!.point) // persisted point matches credited amount
    }

    @Test
    fun `owner confirming its own order rewards and clears the qr immediately`() {
        val (uuid, ref) = newOrder(owner = "node-a") // service is node-a
        service.confirm(BankConfirmation(ref, "TX1", 50_000))
        assertEquals(PaymentStatus.SUCCESS, bank.findByReference(ref)!!.status)
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(listOf(uuid), cleared)
    }

    @Test
    fun `a confirmer resolving another node's order only flips it and defers the reward`() {
        val (uuid, ref) = newOrder(owner = "node-b") // service is node-a: not the owner
        service.confirm(BankConfirmation(ref, "TX1", 50_000))

        val order = bank.findByReference(ref)!!
        assertEquals(PaymentStatus.SUCCESS, order.status) // financial flip runs on any node
        assertEquals(false, order.rewardApplied)          // reward left for the owner
        assertEquals(0L, fakeCurrency.balance(uuid))
        assertTrue(enqueued.isEmpty())
        assertTrue(cleared.isEmpty())

        // The owning node (node-b) reconciles and delivers the reward, clearing its QR.
        val ownerService = buildService(serverId = "node-b")
        ownerService.reapplyReward(order)
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(listOf(uuid), cleared)
    }

    @Test
    fun `reconcile after a credit gap applies the credit once without re-adding totals`() {
        val (uuid, ref) = newOrder()
        // Simulate a crash after the atomic commit but before the gated credit:
        // flip the order to SUCCESS with totals, leaving reward_applied = 0.
        database.transaction { conn ->
            bank.resolveSuccessWithinTxn(conn, ref, 50, 2_000)
            totals.add(conn, uuid, "bank", 50_000, 50, 2_000)
        }
        val order = bank.findByReference(ref)!!
        service.reapplyReward(order)
        service.reapplyReward(order) // second reconcile is a no-op
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(50_000L, totalAll(uuid))
    }

    @Test
    fun `a transfer naming a pending order at the wrong amount is reported once and never credited`() {
        val (uuid, ref) = newOrder(amount = 50_000)

        val stray = UnmatchedTransfer("T1", "CK $ref NAP", 20_000)
        service.reportUnmatched(stray)
        service.reportUnmatched(stray) // a repeated poll of the same bank transaction

        assertEquals(PaymentStatus.PENDING, bank.findByReference(ref)!!.status, "it must never be credited")
        assertEquals(0L, fakeCurrency.balance(uuid))
        assertEquals(0L, totalAll(uuid))
        // Reported once: the second call finds the marker already recorded.
        assertTrue(
            ProcessedBankTxDao(database).insertIfAbsent(ProcessedBankTxDao.mismatchKey("T1"), ref, 3_000).not(),
            "the mismatch marker must have been recorded on the first report",
        )
    }

    @Test
    fun `reporting a stray transfer cannot block a later credit carrying the same bank tx id`() {
        // The money-loss guard, at the service level. The mismatch marker is keyed away from the
        // credit guard's bare tx id, so recording it leaves the confirmation path completely free —
        // even for the very same transaction id.
        val (uuid, ref) = newOrder(amount = 50_000)
        service.reportUnmatched(UnmatchedTransfer("T1", "CK $ref NAP", 20_000))

        service.confirm(BankConfirmation(ref, "T1", 50_000))

        assertEquals(PaymentStatus.SUCCESS, bank.findByReference(ref)!!.status, "the credit must still go through")
        assertEquals(50L, fakeCurrency.balance(uuid))
    }

    @Test
    fun `a transfer paying the correct amount is never reported, even if the poll missed the order`() {
        // The poll matches against at most MAX_BATCH pending orders. With a bigger backlog, a perfectly
        // correct transfer for an order outside that window arrives as unmatched. Reporting it would
        // tell the operator it was NOT credited — they would pay by hand, and the next poll would
        // credit it too. Same amount means "the poll just hasn't got to it": stay quiet.
        val (_, ref) = newOrder(amount = 50_000)

        service.reportUnmatched(UnmatchedTransfer("T1", "CK $ref NAP", 50_000))

        assertTrue(
            ProcessedBankTxDao(database).insertIfAbsent(ProcessedBankTxDao.mismatchKey("T1"), ref, 3_000),
            "an exact-amount transfer must leave no mismatch marker and no warning",
        )
    }

    @Test
    fun `a transfer naming no pending order is not reported`() {
        service.reportUnmatched(UnmatchedTransfer("T9", "CK SOMEONE ELSE", 20_000))

        assertTrue(
            ProcessedBankTxDao(database).insertIfAbsent(ProcessedBankTxDao.mismatchKey("T9"), "X", 3_000),
            "a transfer naming nothing of ours must leave no marker and no warning",
        )
    }
}
