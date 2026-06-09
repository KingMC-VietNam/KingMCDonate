package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class CardPaymentDaoTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var dao: CardPaymentDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply {
            connect()
            migrate()
        }
        dao = CardPaymentDao(database)
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    private fun insert(uuid: UUID = UUID.randomUUID()): String =
        dao.insertPending(uuid, "Alice", "VIETTEL", 10_000, "seri", "pin", "thesieutoc", "node-a", 1_000)

    @Test
    fun `resolve rewards exactly once`() {
        val ref = insert()
        assertEquals(1, dao.resolve(ref, PaymentStatus.SUCCESS, 100, 2_000))
        // Second resolve finds no non-terminal row -> no reward.
        assertEquals(0, dao.resolve(ref, PaymentStatus.SUCCESS, 100, 3_000))
    }

    @Test
    fun `waiting orders are listed for their owner server and then resolvable`() {
        val ref = insert()
        assertEquals(1, dao.markWaiting(ref, "T1", 2_000))

        val waiting = dao.findWaitingByServer("node-a")
        assertEquals(1, waiting.size)
        assertEquals("T1", waiting.first().transactionId)
        assertEquals(PaymentStatus.WAITING, waiting.first().status)

        assertEquals(1, dao.resolve(ref, PaymentStatus.SUCCESS, 100, 3_000))
        assertEquals(0, dao.findWaitingByServer("node-a").size)
    }

    @Test
    fun `reference codes are unique across inserts`() {
        assertNotEquals(insert(), insert())
    }

    @Test
    fun `history returns a player's payments newest first`() {
        val uuid = UUID.randomUUID()
        dao.insertPending(uuid, "Bob", "VIETTEL", 10_000, "s1", "p1", "thesieutoc", "node-a", 1_000)
        dao.insertPending(uuid, "Bob", "MOBIFONE", 20_000, "s2", "p2", "thesieutoc", "node-a", 5_000)
        val history = dao.findByPlayer(uuid, 10)
        assertEquals(2, history.size)
        assertEquals(5_000, history.first().createdAt)
    }
}
