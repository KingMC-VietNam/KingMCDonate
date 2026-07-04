package net.kingmc.plugin.kingmcdonate.bedrock

import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardFormSelectionTest {

    private val types = listOf(CardType.VIETTEL, CardType.MOBIFONE)
    private val denoms = listOf(10_000L to 10L, 50_000L to 55L)

    @Test
    fun `resolves a valid selection and trims serial and pin`() {
        val r = CardFormSelection.resolve(types, denoms, typeIndex = 1, priceIndex = 0, serial = " ABC123 ", pin = " 9999 ")
        assertTrue(r is CardFormSelection.Ok)
        r as CardFormSelection.Ok
        assertEquals(CardType.MOBIFONE, r.type)
        assertEquals(10_000L, r.amount)
        assertEquals(10L, r.point)
        assertEquals("ABC123", r.serial)
        assertEquals("9999", r.pin)
    }

    @Test
    fun `rejects an out-of-range type index`() {
        assertEquals(CardFormSelection.Reason.TYPE_INDEX, reason(CardFormSelection.resolve(types, denoms, 5, 0, "s", "p")))
    }

    @Test
    fun `rejects an out-of-range price index`() {
        assertEquals(CardFormSelection.Reason.PRICE_INDEX, reason(CardFormSelection.resolve(types, denoms, 0, 9, "s", "p")))
    }

    @Test
    fun `rejects a blank serial`() {
        assertEquals(CardFormSelection.Reason.EMPTY_SERIAL, reason(CardFormSelection.resolve(types, denoms, 0, 0, "   ", "p")))
    }

    @Test
    fun `rejects a null or blank pin`() {
        assertEquals(CardFormSelection.Reason.EMPTY_PIN, reason(CardFormSelection.resolve(types, denoms, 0, 0, "s", null)))
    }

    private fun reason(r: CardFormSelection) = (r as CardFormSelection.Invalid).reason
}
