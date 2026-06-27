package net.kingmc.plugin.kingmcdonate.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscordServiceTest {

    @Test
    fun `mask keeps only the trailing visible characters`() {
        assertEquals("*******8901", DiscordService.mask("12345678901", 4).let { it })
        assertEquals("****", DiscordService.mask("1234", 0))
        assertEquals("1234", DiscordService.mask("1234", 10)) // visible >= length: unchanged
    }

    @Test
    fun `buildPayload substitutes placeholders in nested strings and converts color`() {
        val template = mapOf(
            "content" to "Hi %PLAYER%",
            "embeds" to listOf(
                mapOf("title" to "T %AMOUNT%", "color" to "#58b9ff"),
            ),
        )
        val out = DiscordService.buildPayload(template, mapOf("PLAYER" to "Alice", "AMOUNT" to "100.000đ"))
        assertEquals("Hi Alice", out["content"])
        @Suppress("UNCHECKED_CAST")
        val embed = (out["embeds"] as List<Map<String, Any?>>)[0]
        assertEquals("T 100.000đ", embed["title"])
        assertEquals(0x58b9ff, embed["color"])
    }
}
