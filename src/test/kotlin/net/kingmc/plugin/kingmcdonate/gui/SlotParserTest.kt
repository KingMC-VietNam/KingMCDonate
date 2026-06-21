package net.kingmc.plugin.kingmcdonate.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlotParserTest {

    @Test
    fun `single int parses to one slot`() {
        assertEquals(listOf(5), SlotParser.parse(5))
    }

    @Test
    fun `range string expands inclusively`() {
        assertEquals((0..8).toList(), SlotParser.parse("0-8"))
    }

    @Test
    fun `comma list parses each entry`() {
        assertEquals(listOf(1, 3, 5), SlotParser.parse("1,3,5"))
    }

    @Test
    fun `mixed list of ranges and ints flattens and dedupes`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14), SlotParser.parse(listOf("0-8", 10, "12,14")))
    }

    @Test
    fun `mixed string combines range and singles`() {
        assertEquals(listOf(0, 1, 2, 10), SlotParser.parse("0-2,10"))
    }

    @Test
    fun `invalid and reversed ranges are dropped`() {
        assertEquals(emptyList<Int>(), SlotParser.parse("8-0"))
        assertEquals(emptyList<Int>(), SlotParser.parse("abc"))
        assertEquals(emptyList<Int>(), SlotParser.parse(null))
    }
}
