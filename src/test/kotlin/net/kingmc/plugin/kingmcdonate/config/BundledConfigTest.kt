package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.discord.DiscordConfig
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneConfig
import net.kingmc.plugin.kingmcdonate.promo.PromoConfig
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards that the shipped resource files use the keyed-section format and parse cleanly
 * through their typed views. A list-style container would be read as empty by the new
 * parsers, so each check asserts the container is NOT a list.
 */
class BundledConfigTest {

    private fun load(resource: String): YamlConfiguration {
        val text = javaClass.getResourceAsStream("/$resource")!!.bufferedReader().readText()
        return YamlConfiguration().apply { loadFromString(text) }
    }

    @Test
    fun `khuyenmai promotions is a keyed section, not a list`() {
        val y = load("khuyenmai.yml")
        assertNull(y.getList("promotions"), "promotions must be a keyed section ({}), not a list")
        assertTrue(PromoConfig(y.getConfigurationSection("promotions")).promotions.isEmpty())
    }

    @Test
    fun `mocnap and mocnaptong periods are keyed sections, not lists`() {
        for (file in listOf("mocnap.yml", "mocnaptong.yml")) {
            val y = load(file)
            for (period in listOf("all", "day", "week", "month")) {
                assertNull(y.getList("milestones.$period"), "$file milestones.$period must not be a list")
            }
            MilestoneConfig(y.getConfigurationSection("milestones")) // parses without throwing
        }
    }

    @Test
    fun `discord default is a keyed hook readable by DiscordConfig`() {
        val y = load("discord.yml")
        assertNull(y.getList("default"), "default must be a keyed section, not a list")
        val hooks = DiscordConfig(y).hooksFor(DiscordConfig.EVENT_CARD)
        assertEquals(1, hooks.size)
        assertTrue(hooks.single().url.isNotBlank())
    }
}
