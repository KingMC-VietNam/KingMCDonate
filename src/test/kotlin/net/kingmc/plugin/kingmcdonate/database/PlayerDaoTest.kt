package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class PlayerDaoTest {

    @TempDir
    lateinit var tempDir: File
    private lateinit var database: Database
    private lateinit var dao: PlayerDao
    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        dao = PlayerDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    @Test
    fun `claimFirstTopup succeeds once then fails`() {
        val uuid = UUID.randomUUID()
        dao.upsert(uuid, "Alice")
        assertTrue(dao.claimFirstTopup(uuid))
        assertFalse(dao.claimFirstTopup(uuid))
    }

    @Test
    fun `claimFirstTopup inserts the player row when absent`() {
        val uuid = UUID.randomUUID()
        // No prior upsert: the claim must still work (insert-then-update path).
        assertTrue(dao.claimFirstTopup(uuid))
        assertFalse(dao.claimFirstTopup(uuid))
    }
}
