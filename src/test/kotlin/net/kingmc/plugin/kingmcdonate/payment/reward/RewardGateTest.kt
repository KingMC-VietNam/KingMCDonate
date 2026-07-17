package net.kingmc.plugin.kingmcdonate.payment.reward

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyProvider
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
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
 * The gate claims before it credits, so it can never double-pay — but a failed credit then leaves
 * an order that looks paid. The ledger is what tells the two apart, so it must be booked only when
 * the credit actually landed.
 */
class RewardGateTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database

    private val uuid: UUID = UUID.randomUUID()
    private var claimed = 0
    private lateinit var audited: MutableList<String>
    private lateinit var enqueued: MutableList<UUID>

    /** Credits normally, or throws as a dead backend would. */
    private class Backend(val fail: Boolean) : CurrencyProvider {
        override val name = "fake"
        val balances = HashMap<UUID, Long>()
        override fun isAvailable() = true
        override fun give(uuid: UUID, amount: Long) {
            if (fail) throw IllegalStateException("backend is down")
            balances[uuid] = (balances[uuid] ?: 0) + amount
        }
        override fun balance(uuid: UUID) = balances[uuid] ?: 0
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        claimed = 0
        audited = mutableListOf()
        enqueued = mutableListOf()
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun gate(backend: Backend): RewardGate {
        val cfg = PluginConfig(YamlConfiguration().apply { loadFromString("server-id: \"node-a\"\n") })
        val currency = CurrencyRegistry(logger) { backend }.apply { load(cfg.currency) }
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {
                enqueued.add(playerUuid)
            }
        }
        val success = DonationSuccessService(
            rewardSink = sink, playerDao = PlayerDao(database), logger = logger, config = { cfg }, broadcaster = {},
        ).apply { auditHook = { audited.add(it.referenceCode) } }
        return RewardGate(currency, success, logger)
    }

    private fun donation() = Donation(uuid, "Alice", "card", 50_000, 50, "KMD7X9A2QP", "card-success", "fakecard")

    private fun RewardGate.run(backendClaims: Int = 1) =
        applyOnce("Card KMD7X9A2QP", uuid, 50, claim = { claimed++; backendClaims }) { donation() }

    @Test
    fun `a credit that lands is booked in the ledger`() {
        val backend = Backend(fail = false)

        gate(backend).run()

        assertEquals(50L, backend.balance(uuid))
        assertEquals(listOf("KMD7X9A2QP"), audited, "a landed credit must be booked")
    }

    @Test
    fun `a credit that throws books nothing, so the ledger never claims points the player lacks`() {
        val backend = Backend(fail = true)

        gate(backend).run()

        assertEquals(0L, backend.balance(uuid))
        assertTrue(audited.isEmpty(), "revenue must not be booked for a credit that never landed")
    }

    @Test
    fun `a credit that throws still runs the rest of post-success`() {
        // The order is SUCCESS and the money is real: the reward is owed either way. Only the ledger
        // is gated — widening this would silently drop rewards on a currency hiccup.
        gate(Backend(fail = true)).run()

        assertEquals(listOf(uuid), enqueued, "the reward must still be enqueued")
    }

    @Test
    fun `losing the claim credits nothing and books nothing`() {
        val backend = Backend(fail = false)

        gate(backend).run(backendClaims = 0)

        assertEquals(0L, backend.balance(uuid))
        assertTrue(audited.isEmpty())
        assertTrue(enqueued.isEmpty(), "the winner already did the post-success work")
    }
}
