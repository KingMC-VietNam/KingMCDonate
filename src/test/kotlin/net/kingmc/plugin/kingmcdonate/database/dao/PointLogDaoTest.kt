package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.model.PointLogEntry
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class PointLogDaoTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var dao: PointLogDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        dao = PointLogDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun entry(
        uuid: UUID,
        amount: Long,
        method: String = "bank",
        provider: String? = "sepay",
        actor: String? = null,
        referenceCode: String? = "REF-$amount",
        createdAt: Long,
    ) = PointLogEntry(
        playerUuid = uuid,
        playerName = "Alice",
        amount = amount,
        method = method,
        provider = provider,
        referenceCode = referenceCode,
        actor = actor,
        server = "node-a",
        content = "Bank top-up sepay",
        createdAt = createdAt,
    )

    @Test
    fun `records a row and reads it back with every field intact`() {
        val uuid = UUID.randomUUID()
        dao.record(entry(uuid, amount = 50, actor = "AdminSteve", createdAt = 1000))

        val rows = dao.findByPlayer(uuid, 10)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(uuid, row.playerUuid)
        assertEquals("Alice", row.playerName)
        assertEquals(50L, row.amount)
        assertEquals("bank", row.method)
        assertEquals("sepay", row.provider)
        assertEquals("REF-50", row.referenceCode)
        assertEquals("AdminSteve", row.actor)
        assertEquals("node-a", row.server)
        assertEquals("Bank top-up sepay", row.content)
        assertEquals(1000L, row.createdAt)
    }

    @Test
    fun `nullable columns round-trip as null`() {
        val uuid = UUID.randomUUID()
        dao.record(entry(uuid, amount = 10, provider = null, actor = null, createdAt = 1))

        val row = dao.findByPlayer(uuid, 10).single()
        assertNull(row.provider)
        assertNull(row.actor)
    }

    @Test
    fun `findByPlayer returns newest first and honours the limit`() {
        val uuid = UUID.randomUUID()
        dao.record(entry(uuid, amount = 1, createdAt = 100))
        dao.record(entry(uuid, amount = 2, createdAt = 300))
        dao.record(entry(uuid, amount = 3, createdAt = 200))

        val rows = dao.findByPlayer(uuid, 2)
        assertEquals(2, rows.size)
        assertEquals(listOf(2L, 3L), rows.map { it.amount }) // 300, then 200
    }

    @Test
    fun `a re-entered reference and method books the ledger row once`() {
        // D1: onSuccess can run twice (poll + webhook, retry). The (reference_code, method) key must
        // dedup so revenue is not double-counted; the second record is swallowed, not an error.
        val uuid = UUID.randomUUID()
        dao.record(entry(uuid, amount = 50, method = "card", referenceCode = "REF-DUP", createdAt = 1000))
        dao.record(entry(uuid, amount = 50, method = "card", referenceCode = "REF-DUP", createdAt = 2000))

        assertEquals(1, dao.findByPlayer(uuid, 10).size)
    }

    @Test
    fun `the same reference under a different method is a distinct ledger row`() {
        val uuid = UUID.randomUUID()
        dao.record(entry(uuid, amount = 50, method = "card", referenceCode = "REF-X", createdAt = 1))
        dao.record(entry(uuid, amount = 50, method = "bank", referenceCode = "REF-X", createdAt = 2))

        assertEquals(2, dao.findByPlayer(uuid, 10).size)
    }

    @Test
    fun `rows with no reference are never deduped`() {
        // Manual /give rows carry a null reference; NULLs are distinct in a UNIQUE index, so repeated
        // admin gives must each be recorded rather than collapsing into one.
        val uuid = UUID.randomUUID()
        dao.record(entry(uuid, amount = 5, method = "give", referenceCode = null, createdAt = 1))
        dao.record(entry(uuid, amount = 7, method = "give", referenceCode = null, createdAt = 2))

        assertEquals(2, dao.findByPlayer(uuid, 10).size)
    }

    @Test
    fun `findByPlayer isolates rows per player`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        dao.record(entry(a, amount = 1, createdAt = 1))
        dao.record(entry(b, amount = 2, createdAt = 2))

        assertEquals(listOf(1L), dao.findByPlayer(a, 10).map { it.amount })
        assertEquals(listOf(2L), dao.findByPlayer(b, 10).map { it.amount })
    }
}
