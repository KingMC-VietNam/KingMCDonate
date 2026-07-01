package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class DonationSuccessServiceTest {

    @TempDir
    lateinit var tempDir: File
    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var playerDao: PlayerDao
    private lateinit var enqueued: MutableList<RewardPayload>
    private val milestoneCalls = mutableListOf<Donation>()
    private val discordCalls = mutableListOf<Donation>()
    private val broadcastCalls = mutableListOf<Donation>()
    private val eventCalls = mutableListOf<Donation>()

    private fun config(yaml: String): PluginConfig {
        val y = YamlConfiguration(); y.loadFromString(yaml); return PluginConfig(y)
    }

    private fun service(cfg: PluginConfig): DonationSuccessService {
        enqueued = mutableListOf()
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) { enqueued.add(payload) }
        }
        return DonationSuccessService(
            rewardSink = sink,
            playerDao = playerDao,
            logger = logger,
            config = { cfg },
            broadcaster = { broadcastCalls.add(it) },
        ).apply {
            milestoneHook = { milestoneCalls.add(it) }
            discordHook = { discordCalls.add(it) }
            leaderboardHook = { }
            bossbarHook = { }
            eventHook = { eventCalls.add(it) }
        }
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        playerDao = PlayerDao(database)
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun donation(uuid: UUID) = Donation(uuid, "Alice", "card", 100_000, 1000, "REF1", MessageKeys.CARD_SUCCESS, "card2k")

    @Test
    fun `enqueues success message with reward commands and calls hooks`() {
        val cfg = config(
            """
            rewards:
              commands:
                "0":
                  - "console: say {player} {amount} {point} {ref}"
            """.trimIndent(),
        )
        val uuid = UUID.randomUUID()
        service(cfg).onSuccess(donation(uuid))
        assertEquals(1, enqueued.size)
        assertEquals(listOf("console: say Alice 100000 1000 REF1"), enqueued[0].commands)
        assertEquals(1, milestoneCalls.size)
        assertEquals(1, discordCalls.size)
        assertEquals(1, eventCalls.size)
    }

    @Test
    fun `first-topup runs once for the first successful donation`() {
        val cfg = config(
            """
            first-topup:
              enabled: true
              commands:
                - "console: give {player} kit starter"
            """.trimIndent(),
        )
        val uuid = UUID.randomUUID()
        val s = service(cfg)
        s.onSuccess(donation(uuid))
        // First donation: a second outbox payload carries the first-topup commands.
        assertEquals(listOf("console: give Alice kit starter"), enqueued.last().commands)
        val countAfterFirst = enqueued.size
        s.onSuccess(donation(uuid))
        // Second donation: no additional first-topup payload (only the success payload).
        assertEquals(countAfterFirst + 1, enqueued.size)
    }

    @Test
    fun `broadcast runs only when enabled`() {
        val off = config("broadcast:\n  on-success: false\n")
        service(off).onSuccess(donation(UUID.randomUUID()))
        assertEquals(0, broadcastCalls.size)

        val on = config("broadcast:\n  on-success: true\n  format: \"&a{player}\"\n")
        service(on).onSuccess(donation(UUID.randomUUID()))
        assertEquals(1, broadcastCalls.size)
    }
}
