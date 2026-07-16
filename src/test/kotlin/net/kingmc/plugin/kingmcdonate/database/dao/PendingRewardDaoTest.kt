package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
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
 * The outbox is at-most-once: a row is marked delivered in the same statement that claims it,
 * so no two nodes — and no redelivery after a crash — can ever run one payload twice.
 */
class PendingRewardDaoTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var dao: PendingRewardDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        dao = PendingRewardDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun enqueue(uuid: UUID = UUID.randomUUID(), ref: String = "KMD7X9A2QP", at: Long = 1_000): Long {
        dao.enqueue(uuid, ref, """{"message":"hi"}""", at)
        return dao.findClaimable(10).single { it.referenceCode == ref }.id
    }

    @Test
    fun `the first caller wins the row and a second caller gets nothing`() {
        val id = enqueue()

        assertEquals(1, dao.claimAndDeliver(id, "node-a", 2_000), "the first node must win exactly once")
        assertEquals(0, dao.claimAndDeliver(id, "node-b", 2_001), "a second node must never win the same row")
    }

    @Test
    fun `winning the row marks it delivered in the same statement`() {
        val id = enqueue()

        dao.claimAndDeliver(id, "node-a", 2_000)

        // Nothing else ran yet — the row is already delivered, so a crash here loses the reward
        // rather than replaying its commands.
        assertTrue(dao.findClaimable(10).none { it.id == id }, "a won row must not be claimable again")
    }

    @Test
    fun `an unclaimed row stays claimable`() {
        val id = enqueue()

        assertTrue(dao.findClaimable(10).any { it.id == id })
    }

    @Test
    fun `claiming one row leaves the others alone`() {
        val uuid = UUID.randomUUID()
        val first = enqueue(uuid, ref = "KMDFIRST01")
        val second = enqueue(uuid, ref = "KMDSECOND2")

        dao.claimAndDeliver(first, "node-a", 2_000)

        val claimable = dao.findClaimable(10).map { it.id }
        assertTrue(claimable.contains(second), "an untouched row must remain claimable")
        assertTrue(!claimable.contains(first), "the won row must be gone")
    }

    @Test
    fun `a per-player lookup skips a row already won`() {
        val uuid = UUID.randomUUID()
        val id = enqueue(uuid)

        dao.claimAndDeliver(id, "node-a", 2_000)

        assertTrue(dao.findClaimableFor(listOf(uuid)).isEmpty(), "the join path must not redeliver a won row")
    }

    @Test
    fun `a per-player lookup for nobody returns nothing`() {
        enqueue()

        assertTrue(dao.findClaimableFor(emptyList()).isEmpty(), "an empty input must not fall back to every row")
    }

    @Test
    fun `an enqueued payload is stored verbatim for the delivering node`() {
        val uuid = UUID.randomUUID()
        val payload = """{"messageKey":"bank-success","commands":["console: give {player}"]}"""
        dao.enqueue(uuid, "REF1", payload, 1_000)

        assertEquals(payload, dao.findClaimable(10).single().payload)
    }
}
