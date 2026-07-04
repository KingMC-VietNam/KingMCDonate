package net.kingmc.plugin.kingmcdonate.provider.card

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CardTypeTest {

    @Test
    fun `parses every entry case-insensitively`() {
        for (type in CardType.entries) {
            assertEquals(type, CardType.parse(type.name))
            assertEquals(type, CardType.parse(type.name.lowercase()))
        }
    }

    @Test
    fun `mixed case aliases resolve`() {
        assertEquals(CardType.VIETTEL, CardType.parse("Viettel"))
        assertEquals(CardType.MOBIFONE, CardType.parse("mObIfOnE"))
    }

    @Test
    fun `unknown or padded input returns null`() {
        assertNull(CardType.parse("saibay"))
        assertNull(CardType.parse(""))
        assertNull(CardType.parse(" viettel"))
        assertNull(CardType.parse("viettel "))
    }
}
