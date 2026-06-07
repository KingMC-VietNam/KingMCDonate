package net.kingmc.plugin.kingmcdonate.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextTest {

    @Test
    fun `legacy ampersand codes become section codes`() {
        assertEquals("§aHello §lWorld", Text.colorize("&aHello &lWorld"))
    }

    @Test
    fun `uppercase legacy code is lowercased`() {
        assertEquals("§cRed", Text.colorize("&CRed"))
    }

    @Test
    fun `hex is expanded to the vanilla section-x sequence`() {
        assertEquals("§x§5§5§f§f§f§f", Text.colorize("&#55FFFF"))
    }

    @Test
    fun `hex and legacy codes mix correctly`() {
        assertEquals("§x§1§a§2§b§3§cHi§r", Text.colorize("&#1A2B3CHi&r"))
    }

    @Test
    fun `non-color ampersand is left untouched`() {
        assertEquals("Tom & Jerry", Text.colorize("Tom & Jerry"))
    }

    @Test
    fun `money uses dot grouping and dong suffix`() {
        assertEquals("100.000đ", Text.formatMoney(100_000))
        assertEquals("1.000.000đ", Text.formatMoney(1_000_000))
        assertEquals("0đ", Text.formatMoney(0))
    }
}
