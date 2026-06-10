package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CardConfigTest {

    private val card: PluginConfig.CardConfig = run {
        val cfg = YamlConfiguration()
        cfg.loadFromString(
            """
            card:
              card-types:
                - VIETTEL
                - GARENA
              denominations:
                10000: 100
                20000: 200
                50000: 500
            """.trimIndent(),
        )
        PluginConfig.CardConfig(cfg.getConfigurationSection("card"))
    }

    @Test
    fun `card types load as the enabled list`() {
        assertEquals(listOf("VIETTEL", "GARENA"), card.cardTypes)
    }

    @Test
    fun `denominations map every amount to its points`() {
        assertEquals(mapOf(10_000L to 100L, 20_000L to 200L, 50_000L to 500L), card.denominations)
    }

    @Test
    fun `maintenance defaults to false`() {
        assertFalse(card.maintenance)
    }
}
