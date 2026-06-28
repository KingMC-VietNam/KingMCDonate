package net.kingmc.plugin.kingmcdonate.promo

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromoConfigTest {

    private fun config(yaml: String): PromoConfig {
        val y = YamlConfiguration()
        y.loadFromString(yaml)
        return PromoConfig(y.getConfigurationSection("promotions"))
    }

    @Test
    fun `parses keyed promotions with key as name`() {
        val c = config(
            """
            promotions:
              cuoi-tuan-x2:
                rate: 100
                from: "28/06/2026 00:00"
                to: "30/06/2026 23:59"
              tet:
                rate: 50
                from: "01/02/2026 00:00"
                to: "10/02/2026 23:59"
            """.trimIndent(),
        )
        assertEquals(2, c.promotions.size)
        val p = c.promotions.first { it.name == "cuoi-tuan-x2" }
        assertEquals(100.0, p.ratePercent)
        assertTrue(p.fromMillis < p.toMillis)
    }

    @Test
    fun `empty section yields no promotions`() {
        assertEquals(emptyList<PromoConfig.Promo>(), config("promotions: {}").promotions)
    }

    @Test
    fun `entry with unparseable time is skipped`() {
        val c = config(
            """
            promotions:
              bad:
                rate: 10
                from: "not-a-date"
                to: "10/02/2026 23:59"
              good:
                rate: 20
                from: "01/02/2026 00:00"
                to: "10/02/2026 23:59"
            """.trimIndent(),
        )
        assertEquals(listOf("good"), c.promotions.map { it.name })
    }
}
