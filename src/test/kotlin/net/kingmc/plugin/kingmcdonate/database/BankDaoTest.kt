package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class BankDaoTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var bank: BankPaymentDao
    private lateinit var processed: ProcessedBankTxDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply {
            connect()
            migrate()
        }
        bank = BankPaymentDao(database)
        processed = ProcessedBankTxDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun insert(uuid: UUID = UUID.randomUUID()): String =
        bank.insertPending(uuid, 50_000, "sepay", "node-a", 1_000)

    @Test
    fun `reference codes are unique across inserts`() {
        assertNotEquals(insert(), insert())
    }

    @Test
    fun `reference code is a plain eight-char token and is looked up whole`() {
        val ref = bank.insertPending(UUID.randomUUID(), 50_000, "sepay", "node-a", 1_000)
        assertTrue(ref.matches(Regex("[A-Z0-9]{8}")))
        assertEquals(ref, bank.findByReference(ref)?.referenceCode)
    }

    @Test
    fun `pending order is found when the haystack contains its reference at the exact amount`() {
        val ref = bank.insertPending(UUID.randomUUID(), 50_000, "sepay", "node-a", 1_000)
        // Contained (even glued to a prefix) at the right amount -> found.
        assertEquals(ref, bank.findPendingByContainedReference("UNG HO TT$ref NAP", 50_000)?.referenceCode)
        // Wrong amount -> not found.
        assertNull(bank.findPendingByContainedReference("UNG HO TT$ref NAP", 20_000))
        // Reference absent from the haystack -> not found.
        assertNull(bank.findPendingByContainedReference("CK OTHER NAP", 50_000))
    }

    @Test
    fun `status flip to success is single-shot`() {
        val ref = insert()
        val first = database.transaction { conn -> bank.resolveSuccessWithinTxn(conn, ref, 50, 2_000) }
        val second = database.transaction { conn -> bank.resolveSuccessWithinTxn(conn, ref, 50, 3_000) }
        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test
    fun `reward-applied claim is single-shot`() {
        val ref = insert()
        database.transaction { conn -> bank.resolveSuccessWithinTxn(conn, ref, 50, 2_000) }
        assertEquals(1, bank.claimRewardApplied(ref, 3_000))
        assertEquals(0, bank.claimRewardApplied(ref, 4_000))
    }

    @Test
    fun `pending and unrewarded queries are owner-scoped`() {
        val ref = insert()
        assertEquals(1, bank.findPendingByServer("node-a").size)
        assertEquals(0, bank.findPendingByServer("node-b").size)

        database.transaction { conn -> bank.resolveSuccessWithinTxn(conn, ref, 50, 2_000) }
        assertEquals(1, bank.findSuccessUnrewardedByServer("node-a").size)
        bank.claimRewardApplied(ref, 3_000)
        assertEquals(0, bank.findSuccessUnrewardedByServer("node-a").size)
    }

    @Test
    fun `cross-server finders return orders from every owner (the confirmer's match set)`() {
        val a = bank.insertPending(UUID.randomUUID(), 50_000, "sepay", "node-a", 1_000)
        val b = bank.insertPending(UUID.randomUUID(), 50_000, "sepay", "node-b", 1_000)

        // PENDING across all servers, unlike the owner-scoped findPendingByServer.
        assertEquals(setOf(a, b), bank.findPendingAllServers().map { it.referenceCode }.toSet())

        bank.markFailed(a, 5_000)
        bank.markFailed(b, 5_000)
        assertEquals(setOf(a, b), bank.findFailedSinceAllServers(4_000).map { it.referenceCode }.toSet())
        assertTrue(bank.findFailedSinceAllServers(6_000).isEmpty())
    }

    @Test
    fun `timeout marks only pending orders failed`() {
        val ref = insert()
        assertEquals(1, bank.markFailed(ref, 9_000))
        assertEquals(0, bank.markFailed(ref, 9_001))
    }

    @Test
    fun `open-order count and latest-created lookups back the anti-spam guard`() {
        val uuid = UUID.randomUUID()
        assertEquals(0, bank.countOpenByPlayer(uuid), "no orders -> nothing open")
        assertNull(bank.latestCreatedAtByPlayer(uuid), "no orders -> no last timestamp")

        bank.insertPending(uuid, 50_000, "sepay", "node-a", 1_000) // open PENDING
        val terminal = bank.insertPending(uuid, 50_000, "sepay", "node-a", 5_000)
        bank.markFailed(terminal, 5_000) // terminal, and the newest row

        assertEquals(1, bank.countOpenByPlayer(uuid), "only the still-PENDING order is counted as open")
        assertEquals(5_000L, bank.latestCreatedAtByPlayer(uuid), "cooldown reads the newest order's creation time")
        assertEquals(0, bank.countOpenByPlayer(UUID.randomUUID()), "another player's orders never count")
    }

    @Test
    fun `processed tx insert is unique and insertIfAbsent reports first vs duplicate`() {
        assertTrue(processed.insertIfAbsent("TX1", "REF1", 1_000))
        assertFalse(processed.insertIfAbsent("TX1", "REF1", 2_000))
    }

}
