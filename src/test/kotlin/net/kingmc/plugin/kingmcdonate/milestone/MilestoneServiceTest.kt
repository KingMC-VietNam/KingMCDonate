package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.DirectScheduler
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

class MilestoneServiceTest {

    @TempDir
    lateinit var tempDir: File
    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var totals: PlayerTotalsDao
    private lateinit var enqueued: MutableList<RewardPayload>

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        totals = PlayerTotalsDao(database)
        enqueued = mutableListOf()
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun milestoneConfig(yaml: String): MilestoneConfig {
        val y = YamlConfiguration(); y.loadFromString(yaml)
        return MilestoneConfig(y.getConfigurationSection("milestones"))
    }

    private fun service(player: MilestoneConfig, server: MilestoneConfig): MilestoneService {
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) { enqueued.add(payload) }
        }
        return MilestoneService(
            MilestoneDao(database), { player }, { server }, sink, DirectScheduler(),
            logger,
        )
    }

    private fun donation(uuid: UUID, amount: Long) =
        Donation(uuid, "Alice", "card", amount, amount / 100, "REF", MessageKeys.CARD_SUCCESS)

    @Test
    fun `grants every newly-crossed player milestone once (retroactive)`() {
        val player = milestoneConfig(
            """
            milestones:
              all:
                50000:
                  commands: ["console: a {player}"]
                100000:
                  commands: ["console: b {player}"]
                200000:
                  commands: ["console: c {player}"]
            """.trimIndent(),
        )
        val server = milestoneConfig("milestones: {}")
        val uuid = UUID.randomUUID()
        totals.add(uuid, "card", 120_000, 1200, 0) // crosses 50k and 100k at once
        val s = service(player, server)
        s.check(donation(uuid, 120_000))
        assertEquals(listOf("console: a Alice", "console: b Alice"), enqueued.flatMap { it.commands })
        // Running again grants nothing new.
        enqueued.clear()
        s.check(donation(uuid, 0))
        assertEquals(emptyList<String>(), enqueued.flatMap { it.commands })
    }

    @Test
    fun `grants server milestone once via the server runner`() {
        val player = milestoneConfig("milestones: {}")
        val server = milestoneConfig(
            """
            milestones:
              all:
                100000:
                  commands: ["console: server {threshold}"]
            """.trimIndent(),
        )
        totals.add(UUID.randomUUID(), "card", 60_000, 600, 0)
        totals.add(UUID.randomUUID(), "bank", 60_000, 600, 0)
        val s = service(player, server)
        val serverRan = mutableListOf<String>()
        s.serverRewardRunner = { commands, _ -> serverRan.addAll(commands) }
        s.check(donation(UUID.randomUUID(), 0))
        assertEquals(listOf("console: server 100000"), serverRan)
        serverRan.clear()
        s.check(donation(UUID.randomUUID(), 0))
        assertEquals(emptyList<String>(), serverRan)
    }
}
