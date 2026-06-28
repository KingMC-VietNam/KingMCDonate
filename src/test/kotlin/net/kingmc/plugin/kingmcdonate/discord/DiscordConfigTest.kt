package net.kingmc.plugin.kingmcdonate.discord

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordConfigTest {

    private fun config(yaml: String): DiscordConfig {
        val y = YamlConfiguration()
        y.loadFromString(yaml)
        return DiscordConfig(y)
    }

    @Test
    fun `event with no specific hooks falls back to default`() {
        val c = config(
            """
            enabled: true
            serial-visible-chars: 4
            default:
              main:
                enabled: true
                url: "https://d/default"
                payload:
                  content: "default %PLAYER%"
            events:
              card-success:
                card:
                  enabled: true
                  url: "https://d/card"
                  payload:
                    content: "card %PLAYER%"
            """.trimIndent(),
        )
        assertTrue(c.enabled)
        assertEquals(4, c.serialVisibleChars)
        assertEquals("https://d/card", c.hooksFor(DiscordConfig.EVENT_CARD).single().url)
        assertEquals("https://d/default", c.hooksFor(DiscordConfig.EVENT_BANK).single().url)
    }

    @Test
    fun `payload nested structure is preserved`() {
        val c = config(
            """
            enabled: true
            default:
              main:
                enabled: true
                url: "https://d/x"
                payload:
                  embeds:
                    - title: "T %AMOUNT%"
                      color: "#58b9ff"
            """.trimIndent(),
        )
        val hook = c.hooksFor(DiscordConfig.EVENT_CARD).single()
        @Suppress("UNCHECKED_CAST")
        val embeds = hook.payload["embeds"] as List<Map<String, Any?>>
        assertEquals("T %AMOUNT%", embeds[0]["title"])
        assertEquals("#58b9ff", embeds[0]["color"])
    }
}
