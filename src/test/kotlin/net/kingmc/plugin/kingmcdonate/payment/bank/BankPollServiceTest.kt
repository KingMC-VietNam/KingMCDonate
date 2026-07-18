package net.kingmc.plugin.kingmcdonate.payment.bank

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.payment.TestSchedulers
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.promo.PromoConfig
import net.kingmc.plugin.kingmcdonate.promo.PromoService
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.render.QrMapRenderer
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
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
 * The bank timeout path must not drop the expiry notice for an offline player: an order that times
 * out while the player is away is made durable via the reward outbox, mirroring the card-FAILED notice.
 */
class BankPollServiceTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var bank: BankPaymentDao

    private fun config(): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString("server-id: \"node-a\"\nbank:\n  timeout: 30\n")
        return PluginConfig(yaml)
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        bank = BankPaymentDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private class CapturingSink : RewardSink {
        val refs = mutableListOf<String>()
        val payloads = mutableListOf<RewardPayload>()
        override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {
            refs.add(referenceCode)
            payloads.add(payload)
        }
    }

    private object NoopQr : QrMapRenderer {
        override fun show(player: Player, mapBytes: ByteArray) = Unit
        override fun resend(player: Player) = Unit
        override fun clear(player: Player) = Unit
        override fun shutdown() = Unit
    }

    private fun buildService(sink: RewardSink, onlineHere: (UUID) -> Boolean): BankPollService {
        val cfg = config()
        val providers = BankProviderRegistry(logger) { null } // not loaded; the gateway branch is skipped below
        val currency = CurrencyRegistry(logger) { FakeCurrencyProvider(available = true) }.apply { load(cfg.currency) }
        val success = DonationSuccessService(
            rewardSink = object : RewardSink {
                override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) = Unit
            },
            playerDao = PlayerDao(database),
            logger = logger,
            config = { cfg },
            broadcaster = {},
        )
        val confirm = BankConfirmService(
            database, bank, ProcessedBankTxDao(database), PlayerTotalsDao(database), PlayerDao(database),
            currency, PromoService { PromoConfig(emptyList()) }, success, clearQr = {}, logger = logger, config = { cfg },
        )
        return BankPollService(
            bank, providers, confirm, NoopQr, TestSchedulers.direct(), logger, { cfg },
            { Messages(null, "", logger) }, rewardSink = sink, queryGateway = { false }, onlineHere = onlineHere,
        )
    }

    private fun expiredPending(uuid: UUID): String {
        PlayerDao(database).upsert(uuid, "Alice")
        // Created 40 minutes ago — past the 30-minute timeout in config.
        return bank.insertPending(uuid, 50_000, "sepay", "node-a", System.currentTimeMillis() - 40 * 60_000L)
    }

    @Test
    fun `an order that expires while the player is offline is made durable via the outbox`() {
        val uuid = UUID.randomUUID()
        val sink = CapturingSink()
        val ref = expiredPending(uuid)

        buildService(sink, onlineHere = { false }).pollOnce()

        assertEquals(PaymentStatus.FAILED, bank.findByReference(ref)!!.status)
        assertEquals(listOf(ref), sink.refs)
        assertEquals(MessageKeys.BANK_EXPIRED, sink.payloads.single().messageKey)
        assertTrue(sink.payloads.single().commands.isEmpty(), "message-only notice, no reward commands")
    }

    @Test
    fun `a dead node's PENDING order is failed by a live node past the longer grace`() {
        // Owner is node-b; this node is node-a. Owner-scoped timeout never touches it, but past the
        // dead-node grace (3x the 30-min timeout = 90 min) any live node expires it so it is not stranded.
        val uuid = UUID.randomUUID()
        val sink = CapturingSink()
        PlayerDao(database).upsert(uuid, "Alice")
        val ref = bank.insertPending(uuid, 50_000, "sepay", "node-b", System.currentTimeMillis() - 100 * 60_000L)

        buildService(sink, onlineHere = { false }).pollOnce()

        assertEquals(PaymentStatus.FAILED, bank.findByReference(ref)!!.status)
        assertEquals(listOf(ref), sink.refs, "the orphaned order's expiry notice is made durable")
    }

    @Test
    fun `another node's PENDING order within the dead-node grace is left for its owner`() {
        // 40 min old: past the 30-min owner grace (node-b will fail its own) but within the 90-min
        // dead-node grace, so node-a must not pre-empt it.
        val uuid = UUID.randomUUID()
        val sink = CapturingSink()
        PlayerDao(database).upsert(uuid, "Alice")
        val ref = bank.insertPending(uuid, 50_000, "sepay", "node-b", System.currentTimeMillis() - 40 * 60_000L)

        buildService(sink, onlineHere = { false }).pollOnce()

        assertEquals(PaymentStatus.PENDING, bank.findByReference(ref)!!.status)
        assertTrue(sink.refs.isEmpty(), "a live owner still inside the dead-node grace keeps its order")
    }

    @Test
    fun `an order still inside its timeout is left open and never notified`() {
        val uuid = UUID.randomUUID()
        val sink = CapturingSink()
        PlayerDao(database).upsert(uuid, "Alice")
        val ref = bank.insertPending(uuid, 50_000, "sepay", "node-a", System.currentTimeMillis()) // fresh

        buildService(sink, onlineHere = { false }).pollOnce()

        assertEquals(PaymentStatus.PENDING, bank.findByReference(ref)!!.status)
        assertTrue(sink.refs.isEmpty(), "a live order must not be expired or notified")
    }
}
