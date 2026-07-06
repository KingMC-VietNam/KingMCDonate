package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.discord.DiscordConfig
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuDefinition
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
    fun `a configured promotion is parsed only when the promotions section is passed, not the root`() {
        // Reproduces the wiring bug: ConfigManager must hand PromoConfig the `promotions`
        // section (like MilestoneConfig), not the whole document. Passing the root silently
        // drops every promotion, so promo bonuses never apply.
        val y = YamlConfiguration().apply {
            loadFromString(
                """
                promotions:
                  test-x2:
                    rate: 100
                    from: "01/07/2026 00:00"
                    to: "31/07/2026 23:59"
                """.trimIndent(),
            )
        }
        assertTrue(PromoConfig(y).promotions.isEmpty(), "root document must not be treated as the promotions section")
        val promos = PromoConfig(y.getConfigurationSection("promotions")).promotions
        assertEquals(1, promos.size)
        assertEquals(100.0, promos.single().ratePercent)
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
    fun `topnap menu parses with content slots and toggle labels`() {
        val y = load("menus/topnap.yml")
        val def = MenuDefinition.parse("topnap", y)
        assertTrue(def.contentSlots.isNotEmpty(), "topnap must declare paginated content slots")
        assertEquals("Tiền nạp", y.getString("metric-labels.AMOUNT"))
        assertEquals("Tất cả", y.getString("period-labels.ALL"))
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
