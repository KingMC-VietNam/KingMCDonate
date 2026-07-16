package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.card.CardPaymentService
import net.kingmc.plugin.kingmcdonate.payment.card.CardPollService
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
 * The poll's resolvable set covers PENDING as well as WAITING, so a node that died between the
 * PENDING insert and the charge's completion cannot strand a possibly-charged card. The load-bearing
 * rule is that a PENDING order **inside** its timeout is never handed to the gateway: a charge POST
 * may still be in flight, and resolving it FAILED would strand that charge for good.
 */
class CardPollServiceTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var card: CardPaymentDao
    private lateinit var totals: PlayerTotalsDao
    private lateinit var fakeCurrency: FakeCurrencyProvider

    private val uuid: UUID = UUID.randomUUID()

    /** Records every gateway check so a test can assert the gateway was left alone. */
    private class RecordingProvider(private val outcome: (String) -> CardOutcome) : CardProvider {
        val checked = mutableListOf<String>()
        override val name = "fakecard"
        override fun supportedTypes(): Set<CardType> = setOf(CardType.VIETTEL)
        override fun submit(request: CardRequest) = CardOutcome(PaymentStatus.WAITING, null, null, "")
        override fun check(transactionId: String, request: CardRequest): CardOutcome {
            checked += transactionId
            return outcome(transactionId)
        }
    }

    private fun config(): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString(
            "server-id: \"node-a\"\ncard:\n  timeout: 30\n  poll-spacing-millis: 0\n  denominations:\n    50000: 50\n",
        )
        return PluginConfig(yaml)
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        card = CardPaymentDao(database)
        totals = PlayerTotalsDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun buildPoll(
        provider: RecordingProvider,
        queryGateway: Boolean = true,
    ): CardPollService {
        val cfg = config()
        fakeCurrency = FakeCurrencyProvider(available = true)
        val currency = CurrencyRegistry(logger) { fakeCurrency }.apply { load(cfg.currency) }
        val providers = CardProviderRegistry(logger) { provider }.apply { load("fakecard") }
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) = Unit
        }
        val success = DonationSuccessService(
            rewardSink = sink, playerDao = PlayerDao(database), logger = logger, config = { cfg }, broadcaster = {},
        )
        val service = CardPaymentService(
            database, card, totals, PlayerDao(database), currency, providers,
            PromoService { PromoConfig(emptyList()) }, success, TestSchedulers.direct(), logger,
            config = { cfg }, messages = { Messages(null, "", logger) },
            rewardSink = sink, onlineHere = { false },
        )
        return CardPollService(
            card, service, providers, TestSchedulers.direct(), logger,
            config = { cfg }, queryGateway = { queryGateway },
        )
    }

    /** Insert a PENDING order created [ageMinutes] ago, as `submit()` would. */
    private fun pending(ageMinutes: Long): String {
        val createdAt = System.currentTimeMillis() - ageMinutes * 60_000L
        return card.insertPending(uuid, "Alice", CardType.VIETTEL.name, 50_000, "SER", "PIN", "fakecard", "node-a", createdAt)
    }

    private fun status(ref: String) = card.findByReference(ref)!!.status

    @Test
    fun `a PENDING order inside its timeout is never handed to the gateway`() {
        // The node is alive and its charge POST may still be in flight. Checking now can resolve the
        // order FAILED and strand the charge, so the gateway must not be contacted at all.
        val provider = RecordingProvider { CardOutcome(PaymentStatus.FAILED, transactionId = null, recognizedAmount = null, message = "not found") }
        val ref = pending(ageMinutes = 1)

        buildPoll(provider).pollOnce()

        assertTrue(provider.checked.isEmpty(), "a young PENDING order must not be checked; got ${provider.checked}")
        assertEquals(PaymentStatus.PENDING, status(ref), "it must be left exactly as it was")
    }

    @Test
    fun `a PENDING order past its timeout whose card the gateway charged is awarded`() {
        // The node died after the charge landed. The final check finds the money and the player is paid.
        val provider = RecordingProvider { CardOutcome(PaymentStatus.SUCCESS, transactionId = "TX1", recognizedAmount = 50_000, message = "ok") }
        val ref = pending(ageMinutes = 31)

        buildPoll(provider).pollOnce()

        assertEquals(listOf(ref), provider.checked, "the stranded order must be reconciled by reference")
        assertEquals(PaymentStatus.SUCCESS, status(ref))
        assertEquals(50L, fakeCurrency.balance(uuid), "the charged card must be paid out")
    }

    @Test
    fun `a PENDING order past its timeout the gateway does not recognise is closed FAILED`() {
        // The node died before the charge went out: nothing was taken, so the order is simply closed.
        val provider = RecordingProvider { CardOutcome(PaymentStatus.FAILED, transactionId = null, recognizedAmount = null, message = "not found") }
        val ref = pending(ageMinutes = 31)

        buildPoll(provider).pollOnce()

        assertEquals(PaymentStatus.FAILED, status(ref))
        assertEquals(0L, fakeCurrency.balance(uuid), "nothing was charged, so nothing is credited")
    }

    @Test
    fun `a WAITING order inside its timeout is still checked every pass`() {
        val provider = RecordingProvider { CardOutcome(PaymentStatus.SUCCESS, transactionId = "TX1", recognizedAmount = 50_000, message = "ok") }
        val ref = pending(ageMinutes = 1)
        card.markWaiting(ref, "TX1", System.currentTimeMillis())

        buildPoll(provider).pollOnce()

        assertEquals(listOf("TX1"), provider.checked, "WAITING keeps its per-pass check, by transaction id")
        assertEquals(PaymentStatus.SUCCESS, status(ref))
    }

    @Test
    fun `a stranded PENDING order is timed out even when the gateway is not polled`() {
        // webhook-only: the gateway is never queried, but housekeeping must still close the order.
        val provider = RecordingProvider { CardOutcome(PaymentStatus.SUCCESS, transactionId = "TX1", recognizedAmount = 50_000, message = "ok") }
        val ref = pending(ageMinutes = 31)

        buildPoll(provider, queryGateway = false).pollOnce()

        assertTrue(provider.checked.isEmpty(), "webhook-only must not query the gateway")
        assertEquals(PaymentStatus.FAILED, status(ref))
    }
}
