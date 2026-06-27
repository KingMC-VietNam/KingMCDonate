package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.util.Period
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MilestoneConfigTest {

    private fun config(yaml: String): MilestoneConfig {
        val y = YamlConfiguration()
        y.loadFromString(yaml)
        return MilestoneConfig(y.getConfigurationSection("milestones"))
    }

    @Test
    fun `parses entries per period and sorts by threshold`() {
        val c = config(
            """
            milestones:
              all:
                - threshold: 200000
                  message: "&aMốc {threshold}"
                  commands:
                    - "console: give {player} gem 2"
                  bossbar:
                    enabled: true
                    color: BLUE
                    style: SEGMENTED_10
                - threshold: 100000
                  commands: []
              day:
                - threshold: 50000
                  commands: ["console: say {player}"]
            """.trimIndent(),
        )
        val all = c.forPeriod(Period.ALL)
        assertEquals(listOf(100000L, 200000L), all.map { it.threshold })
        assertEquals("&aMốc {threshold}", all[1].message)
        assertTrue(all[1].bossBar.enabled)
        assertEquals("BLUE", all[1].bossBar.color)
        assertEquals(listOf(50000L), c.forPeriod(Period.DAY).map { it.threshold })
        assertEquals(emptyList<Long>(), c.forPeriod(Period.WEEK).map { it.threshold })
    }

    @Test
    fun `missing bossbar defaults to disabled`() {
        val c = config(
            """
            milestones:
              all:
                - threshold: 1000
                  commands: []
            """.trimIndent(),
        )
        val e = c.forPeriod(Period.ALL).single()
        assertEquals(false, e.bossBar.enabled)
    }
}
