package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.card.CardPaymentService
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.promo.PromoConfig
import net.kingmc.plugin.kingmcdonate.promo.PromoService
import net.kingmc.plugin.kingmcdonate.provider.card.CardOutcome
import net.kingmc.plugin.kingmcdonate.provider.card.CardProvider
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardRequest
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
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

/**
 * Characterization tests pinning the money-critical exactly-once guarantees of
 * [CardPaymentService.award] / [applyOutcome] / [reapplyReward] before the reward-gate
 * refactor. Only Bukkit-free paths are exercised: the FAILED / currency-unavailable
 * branches message the player via `Bukkit.getPlayer`, which is unavailable in a plain JVM.
 */
class CardPaymentServiceTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var card: CardPaymentDao
    private lateinit var totals: PlayerTotalsDao
    private lateinit var fakeCurrency: FakeCurrencyProvider
    private lateinit var enqueued: MutableList<UUID>
    private lateinit var payloads: MutableList<RewardPayload>
    private lateinit var service: CardPaymentService

    /** Never contacted in these tests; award/applyOutcome operate on a supplied outcome. */
    private object FakeCardProvider : CardProvider {
        override val name = "fakecard"
        override fun supportedTypes(): Set<CardType> = setOf(CardType.VIETTEL)
        override fun submit(request: CardRequest) = CardOutcome(PaymentStatus.WAITING, null, null, "")
        override fun check(transactionId: String, request: CardRequest) = CardOutcome(PaymentStatus.WAITING, null, null, "")
    }

    private fun config(pointRate: Double = 1.0): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString("server-id: \"node-a\"\ncard:\n  denominations:\n    50000: 50\n")
        return PluginConfig(yaml)
    }

    private fun buildService(promoCfg: PromoConfig = PromoConfig(emptyList())): CardPaymentService {
        val cfg = config()
        fakeCurrency = FakeCurrencyProvider(available = true)
        val currency = CurrencyRegistry(logger) { fakeCurrency }.apply { load(cfg.currency) }
        val providers = CardProviderRegistry(logger) { FakeCardProvider }.apply { load("fakecard") }
        enqueued = mutableListOf()
        payloads = mutableListOf()
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {
                enqueued.add(playerUuid)
                payloads.add(payload)
            }
        }
        val success = DonationSuccessService(
            rewardSink = sink,
            playerDao = PlayerDao(database),
            logger = logger,
            config = { cfg },
            broadcaster = {},
        )
        return CardPaymentService(
            database, card, totals, PlayerDao(database), currency, providers,
            PromoService { promoCfg }, success, TestSchedulers.direct(), logger,
            config = { cfg }, messages = { Messages(null, "", logger) },
            rewardSink = sink, onlineHere = { false },
        )
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        card = CardPaymentDao(database)
        totals = PlayerTotalsDao(database)
        service = buildService()
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun pending(uuid: UUID, amount: Long = 50_000): String {
        PlayerDao(database).upsert(uuid, "Alice")
        return card.insertPending(uuid, "Alice", "VIETTEL", amount, "s", "p", "fakecard", "node-a", 1_000)
    }

    private fun totalAll(uuid: UUID): Long = database.withConnection { conn ->
        conn.prepareStatement(
            "SELECT amount_vnd FROM player_totals WHERE player_uuid = ? AND period = 'ALL' AND method = 'card'",
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    @Test
    fun `success awards points, totals and reward exactly once`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        service.award(ref, uuid, "Alice", 50_000, CardOutcome(PaymentStatus.SUCCESS, null, null, ""))
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(50_000L, totalAll(uuid))
        assertEquals(1, enqueued.size)
        assertEquals(PaymentStatus.SUCCESS, card.findByReference(ref)!!.status)
    }

    @Test
    fun `awarding the same order twice credits once`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        val outcome = CardOutcome(PaymentStatus.SUCCESS, null, null, "")
        service.award(ref, uuid, "Alice", 50_000, outcome)
        service.award(ref, uuid, "Alice", 50_000, outcome)
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(50_000L, totalAll(uuid))
        assertEquals(1, enqueued.size)
    }

    @Test
    fun `applyOutcome WAITING stores the transaction handle without crediting`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        service.applyOutcome(ref, uuid, "Alice", 50_000, CardOutcome(PaymentStatus.WAITING, "TX9", null, ""))
        val order = card.findByReference(ref)!!
        assertEquals(PaymentStatus.WAITING, order.status)
        assertEquals("TX9", order.transactionId)
        assertEquals(0L, fakeCurrency.balance(uuid))
    }

    @Test
    fun `a card that fails while the player is offline is enqueued to the outbox as a message-only notice`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        card.markWaiting(ref, "TX", 2_000) // async path: WAITING then FAILED via poll
        // onlineHere = false in the test service → the failure notice must be made durable via the outbox.
        service.applyOutcome(ref, uuid, "Alice", 50_000, CardOutcome(PaymentStatus.FAILED, null, null, "used card"))

        assertEquals(PaymentStatus.FAILED, card.findByReference(ref)!!.status)
        assertEquals(1, payloads.size)
        assertEquals(MessageKeys.CARD_FAILED, payloads[0].messageKey)
        assertTrue(payloads[0].commands.isEmpty()) // message-only, no reward commands
    }

    @Test
    fun `reconcile applies the gated credit once without re-adding totals`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        // Simulate a crash after the atomic commit but before the gated credit.
        database.transaction { conn ->
            card.resolveSuccessWithinTxn(conn, ref, 50, 2_000)
            totals.add(conn, uuid, "card", 50_000, 50, 2_000)
        }
        val order = card.findByReference(ref)!!
        service.reapplyReward(order)
        service.reapplyReward(order) // second reconcile is a no-op
        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(50_000L, totalAll(uuid))
    }

    @Test
    fun `a charged card with the currency backend down is recorded SUCCESS with the credit deferred`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        fakeCurrency.available = false // the backend goes down after startup, mid-flight

        service.award(ref, uuid, "Alice", 50_000, CardOutcome(PaymentStatus.SUCCESS, null, null, ""))

        // The charge is banked (status + totals) but reward_applied stays 0, so the order is not
        // left WAITING to be timed out — in webhook-only mode nothing would ever re-check it.
        assertEquals(PaymentStatus.SUCCESS, card.findByReference(ref)!!.status)
        assertEquals(50_000L, totalAll(uuid))
        assertEquals(0L, fakeCurrency.balance(uuid), "points must not be credited while the backend is down")
        assertEquals(
            listOf(ref),
            card.findSuccessUnrewardedByServer("node-a").map { it.referenceCode },
            "the reconcile pass must be able to find it",
        )
    }

    @Test
    fun `the deferred credit lands exactly once once the currency backend returns`() {
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        fakeCurrency.available = false
        service.award(ref, uuid, "Alice", 50_000, CardOutcome(PaymentStatus.SUCCESS, null, null, ""))

        fakeCurrency.available = true
        val order = card.findByReference(ref)!!
        service.reapplyReward(order)
        service.reapplyReward(order) // a second pass must not credit again

        assertEquals(50L, fakeCurrency.balance(uuid))
        assertEquals(50_000L, totalAll(uuid), "totals were committed once, at award time")
        assertTrue(card.findSuccessUnrewardedByServer("node-a").isEmpty(), "the order is no longer unrewarded")
    }

    @Test
    fun `active promo increases credited points and persisted point`() {
        val now = System.currentTimeMillis()
        service = buildService(promoCfg = PromoConfig(listOf(PromoConfig.Promo("x", 100.0, now - 1000, now + 60_000))))
        val uuid = UUID.randomUUID()
        val ref = pending(uuid)
        service.award(ref, uuid, "Alice", 50_000, CardOutcome(PaymentStatus.SUCCESS, null, null, ""))
        assertEquals(100L, fakeCurrency.balance(uuid)) // 50 base * (1 + 100/100)
        assertEquals(50_000L, totalAll(uuid))          // face amount unaffected by promo
        assertEquals(100L, card.findByReference(ref)!!.point)
    }
}
