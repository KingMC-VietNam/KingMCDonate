package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
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
        dao.insertPending(uuid, "Alice", "VIETTEL", 10_000, "seri", "pin", "card2k", "node-a", 1_000)

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
    fun `findWaitingAllServers returns waiting orders from every owner (the confirmer's check set)`() {
        val a = dao.insertPending(UUID.randomUUID(), "Alice", "VIETTEL", 10_000, "s", "p", "card2k", "node-a", 1_000)
        val b = dao.insertPending(UUID.randomUUID(), "Bob", "VIETTEL", 10_000, "s", "p", "card2k", "node-b", 1_000)
        dao.markWaiting(a, "TA", 2_000)
        dao.markWaiting(b, "TB", 2_000)

        // Owner-scoped view sees only its own; the confirmer's cross-server view sees both.
        assertEquals(setOf(a), dao.findWaitingByServer("node-a").map { it.referenceCode }.toSet())
        assertEquals(setOf(a, b), dao.findWaitingAllServers().map { it.referenceCode }.toSet())
    }

    @Test
    fun `reference codes are unique across inserts`() {
        assertNotEquals(insert(), insert())
    }

    @Test
    fun `resolveSuccessWithinTxn flips a non-terminal order once`() {
        val ref = insert()
        val first = database.transaction { conn -> dao.resolveSuccessWithinTxn(conn, ref, 100, 2_000) }
        assertEquals(1, first)
        assertEquals(PaymentStatus.SUCCESS, dao.findByReference(ref)!!.status)
        assertEquals(100, dao.findByReference(ref)!!.point)
        // A second flip finds no non-terminal row.
        val second = database.transaction { conn -> dao.resolveSuccessWithinTxn(conn, ref, 100, 3_000) }
        assertEquals(0, second)
    }

    @Test
    fun `claimRewardApplied wins exactly once`() {
        val ref = insert()
        database.transaction { conn -> dao.resolveSuccessWithinTxn(conn, ref, 100, 2_000) }
        assertEquals(1, dao.claimRewardApplied(ref, 2_500))
        // Every later claim loses, so the external credit runs at most once.
        assertEquals(0, dao.claimRewardApplied(ref, 3_000))
        assertEquals(0, dao.claimRewardApplied(ref, 3_500))
    }

    @Test
    fun `findSuccessUnrewardedByServer returns only SUCCESS orders with credit unapplied`() {
        val unrewarded = insert()
        database.transaction { conn -> dao.resolveSuccessWithinTxn(conn, unrewarded, 100, 2_000) }

        val rewarded = insert()
        database.transaction { conn -> dao.resolveSuccessWithinTxn(conn, rewarded, 100, 2_000) }
        dao.claimRewardApplied(rewarded, 2_500)

        val stillWaiting = insert()
        dao.markWaiting(stillWaiting, "T", 2_000)

        val found = dao.findSuccessUnrewardedByServer("node-a")
        assertEquals(1, found.size)
        assertEquals(unrewarded, found.first().referenceCode)

        // Once claimed, it drops out of the reconcile set.
        dao.claimRewardApplied(unrewarded, 2_600)
        assertEquals(0, dao.findSuccessUnrewardedByServer("node-a").size)
    }

    @Test
    fun `history returns a player's payments newest first`() {
        val uuid = UUID.randomUUID()
        dao.insertPending(uuid, "Bob", "VIETTEL", 10_000, "s1", "p1", "card2k", "node-a", 1_000)
        dao.insertPending(uuid, "Bob", "MOBIFONE", 20_000, "s2", "p2", "card2k", "node-a", 5_000)
        val history = dao.findByPlayer(uuid, 10)
        assertEquals(2, history.size)
        assertEquals(5_000, history.first().createdAt)
    }
}
