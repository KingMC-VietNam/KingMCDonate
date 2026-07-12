package net.kingmc.plugin.kingmcdonate.database

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PendingRewardDao
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
    private lateinit var outbox: PendingRewardDao

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply {
            connect()
            migrate()
        }
        bank = BankPaymentDao(database)
        processed = ProcessedBankTxDao(database)
        outbox = PendingRewardDao(database)
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
    fun `processed tx insert is unique and insertIfAbsent reports first vs duplicate`() {
        assertTrue(processed.insertIfAbsent("TX1", "REF1", 1_000))
        assertFalse(processed.insertIfAbsent("TX1", "REF1", 2_000))
    }

    @Test
    fun `outbox claim has a single winner and reaper requeues stale claims`() {
        outbox.enqueue(UUID.randomUUID(), "REF1", "{}", 1_000)
        val row = outbox.findClaimableFor(listOf(claimableUuid())).first()

        assertEquals(1, outbox.claim(row.id, "node-a", 2_000))
        assertEquals(0, outbox.claim(row.id, "node-b", 2_001))

        // Not yet stale: reaper leaves it. Past threshold: requeued and claimable again.
        assertEquals(0, outbox.reapStale(thresholdMillis = 10_000, now = 5_000))
        assertEquals(1, outbox.reapStale(thresholdMillis = 10_000, now = 20_000))
        assertEquals(1, outbox.claim(row.id, "node-b", 21_000))
    }

    @Test
    fun `outbox payload is unchanged after a stale requeue`() {
        val uuid = UUID.randomUUID()
        val payload = """{"messageKey":"bank-success","commands":["console: give {player}"]}"""
        outbox.enqueue(uuid, "REF1", payload, 1_000)
        val row = outbox.findClaimable(10).first()
        outbox.claim(row.id, "node-a", 2_000)
        outbox.reapStale(thresholdMillis = 10_000, now = 20_000)
        val requeued = outbox.findClaimable(10).first()
        assertEquals(payload, requeued.payload)
    }

    @Test
    fun `delivered rows are not claimable`() {
        val uuid = UUID.randomUUID()
        outbox.enqueue(uuid, "REF1", "{}", 1_000)
        val row = outbox.findClaimableFor(listOf(uuid)).first()
        outbox.claim(row.id, "node-a", 2_000)
        outbox.markDelivered(row.id)
        assertTrue(outbox.findClaimableFor(listOf(uuid)).isEmpty())
        assertNull(outbox.findClaimableFor(emptyList()).firstOrNull())
    }

    private fun claimableUuid(): UUID =
        outbox.findClaimableFor(allOutboxUuids()).first().playerUuid

    private fun allOutboxUuids(): List<UUID> = database.withConnection { conn ->
        conn.prepareStatement("SELECT player_uuid FROM pending_reward").use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<UUID>()
                while (rs.next()) out.add(UUID.fromString(rs.getString("player_uuid")))
                out
            }
        }
    }
}
