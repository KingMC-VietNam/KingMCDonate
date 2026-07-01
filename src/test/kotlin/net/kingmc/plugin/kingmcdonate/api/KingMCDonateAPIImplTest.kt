package net.kingmc.plugin.kingmcdonate.api

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.payment.ManualCreditService
import net.kingmc.plugin.kingmcdonate.payment.TestSchedulers
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class KingMCDonateAPIImplTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var totals: PlayerTotalsDao
    private lateinit var players: PlayerDao
    private lateinit var fakeCurrency: FakeCurrencyProvider
    private lateinit var api: KingMCDonateAPIImpl

    private fun config(): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString("server-id: \"node-a\"\nbank:\n  point-rate: 1.0\n")
        return PluginConfig(yaml)
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        totals = PlayerTotalsDao(database)
        players = PlayerDao(database)
        val cfg = config()
        fakeCurrency = FakeCurrencyProvider(available = true)
        val currency = CurrencyRegistry(logger) { fakeCurrency }.apply { load(cfg.currency) }
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {}
        }
        val success = DonationSuccessService(sink, PlayerDao(database), logger, { cfg }, broadcaster = {})
        val manualCredit = ManualCreditService(
            database, CardPaymentDao(database), BankPaymentDao(database), totals, PlayerDao(database),
            currency, success, TestSchedulers.direct(), logger, config = { cfg },
        )
        val leaderboard = LeaderboardService(LeaderboardDao(database), TestSchedulers.direct(), { cfg }, logger)
        api = KingMCDonateAPIImpl(leaderboard, manualCredit) { "Alice" }
    }

    @AfterEach
    fun tearDown() = database.close()

    @Test
    fun `giveManual routes through the manual-credit service and credits once`() {
        val uuid = UUID.randomUUID()
        api.giveManual(uuid, "bank", 50_000, null)
        assertEquals(50L, fakeCurrency.balance(uuid)) // 50_000/1000 * 1.0
    }

    @Test
    fun `giveManual rejects an unknown method`() {
        assertThrows(IllegalArgumentException::class.java) {
            api.giveManual(UUID.randomUUID(), "wallet", 50_000, null)
        }
    }

    @Test
    fun `getTotalVnd and getPoint read settled cache values`() {
        val uuid = UUID.randomUUID()
        players.upsert(uuid, "Alice")
        totals.add(uuid, "card", 137_000, 137, 0)
        api.getTotalVnd(uuid) // first call primes the cache (inline refresh via DirectScheduler)
        assertEquals(137_000L, api.getTotalVnd(uuid))
        assertEquals(137L, api.getPoint(uuid))
    }

    @Test
    fun `getTop ranks entries from 1 in descending order`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        players.upsert(a, "Alice"); players.upsert(b, "Bob")
        totals.add(a, "card", 30_000, 300, 0)
        totals.add(b, "bank", 90_000, 900, 0)
        api.getTop(DonationMetric.AMOUNT, DonationPeriod.ALL) // prime
        val top = api.getTop(DonationMetric.AMOUNT, DonationPeriod.ALL)
        assertEquals(listOf(1, 2), top.map { it.rank })
        assertEquals(listOf("Bob", "Alice"), top.map { it.name })
        assertEquals(listOf(90_000L, 30_000L), top.map { it.value })
    }
}
