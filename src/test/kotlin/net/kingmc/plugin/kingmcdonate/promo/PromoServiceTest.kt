package net.kingmc.plugin.kingmcdonate.promo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PromoServiceTest {

    private fun service(vararg promos: PromoConfig.Promo): PromoService {
        val cfg = PromoConfig(promos.toList())
        return PromoService { cfg }
    }

    @Test
    fun `no active promo gives zero bonus and unchanged point`() {
        val s = service(PromoConfig.Promo("a", 20.0, 1000, 2000))
        assertEquals(0.0, s.activeBonusPercent(5000))
        assertEquals(1000L, s.applyBonus(1000, 5000))
    }

    @Test
    fun `overlapping promos pick the highest rate`() {
        val s = service(
            PromoConfig.Promo("a", 10.0, 0, 10_000),
            PromoConfig.Promo("b", 30.0, 0, 10_000),
            PromoConfig.Promo("c", 20.0, 0, 5_000),
        )
        assertEquals(30.0, s.activeBonusPercent(1000))
        // 1000 * (1 + 30/100) = 1300, rounded.
        assertEquals(1300L, s.applyBonus(1000, 1000))
        // activeEnd returns the end of the chosen (highest-rate) promo.
        assertEquals(10_000L, s.activeEnd(1000))
    }

    @Test
    fun `boundaries are inclusive`() {
        val s = service(PromoConfig.Promo("a", 50.0, 1000, 2000))
        assertEquals(50.0, s.activeBonusPercent(1000))
        assertEquals(50.0, s.activeBonusPercent(2000))
        assertEquals(0.0, s.activeBonusPercent(2001))
    }
}
