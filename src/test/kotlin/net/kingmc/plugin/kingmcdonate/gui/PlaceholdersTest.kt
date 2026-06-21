package net.kingmc.plugin.kingmcdonate.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaceholdersTest {

    @Test
    fun `tokens are substituted`() {
        assertEquals(
            "100.000 VND -> 120 point",
            Placeholders.applyTokens("{amount} VND -> {point} point", mapOf("amount" to "100.000", "point" to "120")),
        )
    }

    @Test
    fun `text without tokens is returned unchanged`() {
        assertEquals("no tokens here", Placeholders.applyTokens("no tokens here", mapOf("amount" to "1")))
    }

    @Test
    fun `unknown papi tokens are left as written when no hook installed`() {
        // papi resolver is null by default in unit tests.
        assertEquals("%player_name%", Placeholders.applyTokens("%player_name%", emptyMap()))
    }
}
