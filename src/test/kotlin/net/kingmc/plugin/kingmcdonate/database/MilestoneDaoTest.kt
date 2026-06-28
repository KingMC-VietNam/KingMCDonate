package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.MilestoneDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class MilestoneDaoTest {

    @TempDir
    lateinit var tempDir: File
    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var dao: MilestoneDao
    private lateinit var totals: PlayerTotalsDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        dao = MilestoneDao(database)
        totals = PlayerTotalsDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    @Test
    fun `player total sums across methods`() {
        val uuid = UUID.randomUUID()
        totals.add(uuid, "card", 30_000, 300, 0)
        totals.add(uuid, "bank", 20_000, 240, 0)
        assertEquals(50_000L, dao.playerTotal(uuid, Period.ALL, "all"))
    }

    @Test
    fun `server total sums across players`() {
        totals.add(UUID.randomUUID(), "card", 30_000, 300, 0)
        totals.add(UUID.randomUUID(), "bank", 20_000, 240, 0)
        assertEquals(50_000L, dao.serverTotal(Period.ALL, "all"))
    }

    @Test
    fun `claimCompletion wins once`() {
        val uuid = UUID.randomUUID().toString()
        assertTrue(dao.claimCompletion(MilestoneDao.SCOPE_PLAYER, uuid, "ALL", "all", 100_000, 0))
        assertFalse(dao.claimCompletion(MilestoneDao.SCOPE_PLAYER, uuid, "ALL", "all", 100_000, 0))
        assertEquals(setOf(100_000L), dao.completedThresholds(MilestoneDao.SCOPE_PLAYER, uuid, "ALL", "all"))
    }
}
