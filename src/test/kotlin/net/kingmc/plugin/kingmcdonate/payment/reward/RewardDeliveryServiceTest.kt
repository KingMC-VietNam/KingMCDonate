package net.kingmc.plugin.kingmcdonate.payment.reward

import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.PendingRewardDao
import net.kingmc.plugin.kingmcdonate.payment.TestSchedulers
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
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
 * Outbox delivery is at-most-once: a row is marked delivered when it is claimed, before its
 * payload runs. These tests drive the decide half ([RewardDeliveryService.claimDeliverable]),
 * which needs no Bukkit `Player` — the test classpath has no mocking framework.
 */
class RewardDeliveryServiceTest {

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

    private fun service(onlineHere: (UUID) -> Boolean) = RewardDeliveryService(
        dao = dao,
        scheduler = TestSchedulers.direct(),
        logger = logger,
        config = { PluginConfig(YamlConfiguration()) },
        messages = { Messages(null, "", logger) },
        onlineHere = onlineHere,
    )

    private fun enqueue(uuid: UUID = UUID.randomUUID(), ref: String = "KMD7X9A2QP", json: String = """{"message":"hi"}"""): UUID {
        dao.enqueue(uuid, ref, json, 1_000)
        return uuid
    }

    private fun rows() = dao.findClaimable(10)

    @Test
    fun `a row this node wins is already marked delivered before anything is dispatched`() {
        enqueue()
        val pending = rows()

        val won = service { true }.claimDeliverable(pending, "node-a", 2_000)

        assertEquals(1, won.size, "the only row must be won")
        // The mark happened inside the claim — not after a dispatch that never ran here.
        assertTrue(rows().isEmpty(), "the won row must already be delivered, before any payload ran")
    }

    @Test
    fun `a second pass over the same rows wins nothing so no payload can run twice`() {
        enqueue()
        val pending = rows()
        val service = service { true }

        service.claimDeliverable(pending, "node-a", 2_000)
        val second = service.claimDeliverable(pending, "node-a", 2_001)

        assertTrue(second.isEmpty(), "a replay of the same rows must win nothing")
    }

    @Test
    fun `another node cannot win a row this node already took`() {
        enqueue()
        val pending = rows()

        service { true }.claimDeliverable(pending, "node-a", 2_000)
        val other = service { true }.claimDeliverable(pending, "node-b", 2_001)

        assertTrue(other.isEmpty(), "a row is run by exactly one node")
    }

    @Test
    fun `a row whose player is not online here is left untouched for another node`() {
        enqueue()
        val pending = rows()

        val won = service { false }.claimDeliverable(pending, "node-a", 2_000)

        assertTrue(won.isEmpty(), "nothing is won when the player is elsewhere")
        assertTrue(rows().isNotEmpty(), "the row must stay claimable so the node holding the player gets it")
    }

    @Test
    fun `an unparseable payload is dropped and its row is still marked delivered`() {
        enqueue(json = "{not json at all")
        val pending = rows()

        val won = service { true }.claimDeliverable(pending, "node-a", 2_000)

        assertTrue(won.isEmpty(), "an unreadable payload must not be dispatched")
        assertTrue(rows().isEmpty(), "a poison row must not be retried forever")
    }

    @Test
    fun `the won row carries its parsed payload`() {
        enqueue(json = """{"message":"thanks","commands":["give %player% diamond"]}""")
        val pending = rows()

        val won = service { true }.claimDeliverable(pending, "node-a", 2_000)

        val (row, payload) = won.single()
        assertEquals("KMD7X9A2QP", row.referenceCode)
        assertEquals("thanks", payload.message)
        assertEquals(listOf("give %player% diamond"), payload.commands)
    }
}
