package net.kingmc.plugin.kingmcdonate.leaderboard

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.DirectScheduler
import net.kingmc.plugin.kingmcdonate.util.Period
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

class LeaderboardServiceTest {

    @TempDir
    lateinit var tempDir: File
    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var totals: PlayerTotalsDao
    private lateinit var players: PlayerDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        totals = PlayerTotalsDao(database)
        players = PlayerDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun service(): LeaderboardService {
        val cfg = PluginConfig(YamlConfiguration())
        return LeaderboardService(LeaderboardDao(database), DirectScheduler(), { cfg }, logger)
    }

    @Test
    fun `top money is ordered descending and resolves names`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        players.upsert(a, "Alice"); players.upsert(b, "Bob")
        totals.add(a, "card", 30_000, 300, 0)
        totals.add(b, "bank", 90_000, 1080, 0)
        val s = service()
        // First call schedules a refresh inline (DirectScheduler), second sees the snapshot.
        s.top(LeaderboardDao.Metric.AMOUNT, Period.ALL)
        val top = s.top(LeaderboardDao.Metric.AMOUNT, Period.ALL)
        assertEquals(listOf("Bob", "Alice"), top.map { it.name })
        assertEquals(listOf(90_000L, 30_000L), top.map { it.value })
    }

    @Test
    fun `top point ranks by points`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        players.upsert(a, "Alice"); players.upsert(b, "Bob")
        totals.add(a, "card", 100_000, 1000, 0)
        totals.add(b, "bank", 50_000, 1200, 0) // fewer VND but more points
        val s = service()
        s.top(LeaderboardDao.Metric.POINT, Period.ALL)
        val top = s.top(LeaderboardDao.Metric.POINT, Period.ALL)
        assertEquals(listOf("Bob", "Alice"), top.map { it.name })
    }
}
