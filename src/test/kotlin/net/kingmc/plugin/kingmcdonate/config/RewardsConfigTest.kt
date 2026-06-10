package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RewardsConfigTest {

    private fun rewards(yaml: String): PluginConfig.RewardsConfig {
        val cfg = YamlConfiguration()
        cfg.loadFromString(yaml)
        return PluginConfig.RewardsConfig(cfg.getConfigurationSection("rewards"))
    }

    @Test
    fun `commandsFor returns the single highest tier at or below the amount`() {
        val r = rewards(
            """
            rewards:
              commands:
                0:
                  - "base"
                50000:
                  - "mid"
                100000:
                  - "high"
            """.trimIndent(),
        )
        assertEquals(listOf("high"), r.commandsFor(100_000))
        assertEquals(listOf("high"), r.commandsFor(120_000))
        assertEquals(listOf("mid"), r.commandsFor(75_000))
        assertEquals(listOf("base"), r.commandsFor(30_000))
    }

    @Test
    fun `commandsFor is empty when no tier qualifies`() {
        val r = rewards(
            """
            rewards:
              commands:
                50000:
                  - "mid"
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), r.commandsFor(10_000))
    }

    @Test
    fun `a missing rewards section yields no commands`() {
        assertEquals(emptyList<String>(), rewards("other: 1").commandsFor(100_000))
    }
}
