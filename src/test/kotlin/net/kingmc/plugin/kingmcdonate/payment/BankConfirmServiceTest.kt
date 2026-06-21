package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.payment.bank.BankConfirmService
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    private lateinit var service: BankConfirmService

    private val config = config(pointRate = 1.0)

    private fun config(pointRate: Double): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString("server-id: \"node-a\"\nbank:\n  point-rate: $pointRate\n")
        return PluginConfig(yaml)
    }

    private fun buildService(currencyAvailable: Boolean = true): BankConfirmService {
        fakeCurrency = FakeCurrencyProvider(available = currencyAvailable)
        val currency = CurrencyRegistry(logger) { fakeCurrency }.apply { load(config.currency) }
        enqueued = mutableListOf()
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {
                enqueued.add(playerUuid)
            }
        }
        return BankConfirmService(
            database, bank, ProcessedBankTxDao(database), totals, PlayerDao(database),
            currency, sink, clearQr = {}, logger = logger, config = { config(1.0) },
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

    private fun newOrder(uuid: UUID = UUID.randomUUID(), amount: Long = 50_000): Pair<UUID, String> {
        PlayerDao(database).upsert(uuid, "Alice")
        return uuid to bank.insertPending(uuid, amount, "sepay", "node-a", 1_000)
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

    @Test
    fun `reconcile after a credit gap applies the credit once without re-adding totals`() {
        val (uuid, ref) = newOrder()
        // Simulate a crash after the atomic commit but before the gated credit:
        // flip the order to SUCCESS with totals, leaving reward_applied = 0.
        database.transaction { conn ->
            bank.resolveSuccessWithinTxn(conn, ref, 2_000)
            totals.add(conn, uuid, "bank", 50_000, 50, 2_000)
        }
        val order = bank.findByReference(ref)!!
        service.reapplyReward(order)
        service.reapplyReward(order) // second reconcile is a no-op
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(50_000L, totalAll(uuid))
    }
}
