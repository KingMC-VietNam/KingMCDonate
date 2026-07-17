package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PointLogDao
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.model.PointLogEntry
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
    fun `the resolvable set covers PENDING as well as WAITING for the owner server`() {
        // PENDING must be in the set: a node that died before its charge completed leaves the order
        // there, and only the poll can reconcile it.
        val stillPending = insert()
        val waiting = insert()
        dao.markWaiting(waiting, "T1", 2_000)
        val done = insert()
        dao.resolve(done, PaymentStatus.SUCCESS, 100, 2_000)
        val otherNode = dao.insertPending(UUID.randomUUID(), "Bob", "VIETTEL", 10_000, "s", "p", "card2k", "node-b", 1_000)

        val refs = dao.findResolvableByServer("node-a").map { it.referenceCode }

        assertEquals(setOf(stillPending, waiting), refs.toSet())
        assertEquals(false, refs.contains(done), "a resolved order is not resolvable again")
        assertEquals(false, refs.contains(otherNode), "polling stays owner-scoped")
    }

    @Test
    fun `a claimed order with no ledger row is reported as a lost credit`() {
        val paid = insert()
        dao.resolve(paid, PaymentStatus.SUCCESS, 100, 2_000)
        dao.claimRewardApplied(paid, 2_000)
        PointLogDao(database).record(
            PointLogEntry(
                playerUuid = UUID.randomUUID(), playerName = "Alice", amount = 100, method = "card",
                provider = "card2k", referenceCode = paid, actor = null, server = "node-a",
                content = "ok", createdAt = 2_000,
            ),
        )
        val lost = insert()
        dao.resolve(lost, PaymentStatus.SUCCESS, 100, 2_000)
        dao.claimRewardApplied(lost, 2_000) // claimed, then the credit threw or the node died

        val refs = dao.findLostCredits(10).map { it.referenceCode }

        assertEquals(listOf(lost), refs, "only the order with no ledger row is a candidate")
    }

    @Test
    fun `an unclaimed or still-open order is not a lost credit`() {
        val unclaimed = insert()
        dao.resolve(unclaimed, PaymentStatus.SUCCESS, 100, 2_000) // reconcile will still credit this one
        insert() // still PENDING

        assertEquals(emptyList<String>(), dao.findLostCredits(10).map { it.referenceCode })
    }

    @Test
    fun `an open order can be re-owned to another node, a resolved one cannot`() {
        val open = insert()
        assertEquals(1, dao.reown(open, "node-b", 5_000))
        assertEquals("node-b", dao.findByReference(open)!!.ownerServer)
        assertEquals(emptyList<String>(), dao.findResolvableByServer("node-a").map { it.referenceCode })

        val done = insert()
        dao.resolve(done, PaymentStatus.SUCCESS, 100, 2_000)
        assertEquals(0, dao.reown(done, "node-b", 5_000), "a finished order must not change hands")
    }

    @Test
    fun `an order awaiting its credit on another node is reported, since only its owner retries it`() {
        // Left by a currency outage: SUCCESS so the stranded-card finder skips it, reward_applied = 0
        // so the lost-credit finder skips it, and reconcile is owner-scoped. If that node never comes
        // back, this is the only thing that sees the order.
        val theirs = dao.insertPending(UUID.randomUUID(), "Bob", "VIETTEL", 10_000, "s", "p", "card2k", "node-b", 1_000)
        dao.resolve(theirs, PaymentStatus.SUCCESS, 100, 2_000)
        val theirsPaid = dao.insertPending(UUID.randomUUID(), "Bob", "VIETTEL", 10_000, "s", "p", "card2k", "node-b", 1_000)
        dao.resolve(theirsPaid, PaymentStatus.SUCCESS, 100, 2_000)
        dao.claimRewardApplied(theirsPaid, 2_000)
        val mine = insert()
        dao.resolve(mine, PaymentStatus.SUCCESS, 100, 2_000)

        val refs = dao.findUnrewardedOnOtherServers("node-a", 10).map { it.referenceCode }

        assertEquals(listOf(theirs), refs)
        assertEquals(false, refs.contains(mine), "this node retries its own on every poll")
    }

    @Test
    fun `the stranded set is the open orders owned by other nodes`() {
        val mine = insert()
        val theirs = dao.insertPending(UUID.randomUUID(), "Bob", "VIETTEL", 10_000, "s", "p", "card2k", "node-b", 1_000)
        val theirsDone = dao.insertPending(UUID.randomUUID(), "Bob", "VIETTEL", 10_000, "s", "p", "card2k", "node-b", 1_000)
        dao.resolve(theirsDone, PaymentStatus.SUCCESS, 100, 2_000)

        val refs = dao.findResolvableOnOtherServers("node-a").map { it.referenceCode }

        assertEquals(listOf(theirs), refs)
        assertEquals(false, refs.contains(mine), "this node's own orders are its poll's job")
    }

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

        val waiting = dao.findResolvableByServer("node-a")
        assertEquals(1, waiting.size)
        assertEquals("T1", waiting.first().transactionId)
        assertEquals(PaymentStatus.WAITING, waiting.first().status)

        assertEquals(1, dao.resolve(ref, PaymentStatus.SUCCESS, 100, 3_000))
        assertEquals(0, dao.findResolvableByServer("node-a").size)
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
