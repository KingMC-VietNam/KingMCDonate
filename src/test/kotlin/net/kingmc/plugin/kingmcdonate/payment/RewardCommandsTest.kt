package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.payment.RewardCommands.Context
import net.kingmc.plugin.kingmcdonate.payment.RewardCommands.Parsed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RewardCommandsTest {

    @Test
    fun `parse detects context prefixes case-insensitively and trims the body`() {
        assertEquals(Parsed(Context.CONSOLE, "broadcast hi"), RewardCommands.parse("console: broadcast hi"))
        assertEquals(Parsed(Context.PLAYER, "give {player} x"), RewardCommands.parse("PLAYER: give {player} x"))
    }

    @Test
    fun `parse defaults to console when no prefix is present`() {
        assertEquals(Parsed(Context.CONSOLE, "say hi"), RewardCommands.parse("say hi"))
    }

    @Test
    fun `parse yields a blank body when only a prefix is given`() {
        assertEquals(Parsed(Context.PLAYER, ""), RewardCommands.parse("player:"))
        assertEquals(Parsed(Context.PLAYER, ""), RewardCommands.parse("player:   "))
    }

    @Test
    fun `format substitutes every token`() {
        val out = RewardCommands.format(
            "give {player} {amount} {point} {ref}",
            mapOf("player" to "Steve", "amount" to "100000", "point" to "1000", "ref" to "ABC"),
        )
        assertEquals("give Steve 100000 1000 ABC", out)
    }
}
