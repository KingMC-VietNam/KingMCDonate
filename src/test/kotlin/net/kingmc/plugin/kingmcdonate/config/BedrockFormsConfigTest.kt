package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BedrockFormsConfigTest {

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text) }

    @Test
    fun `defaults apply when the section is absent`() {
        val c = BedrockFormsConfig(null)
        assertTrue(c.enabled)
        assertTrue(c.card.enabled)
        assertTrue(c.history.enabled)
        assertTrue(c.leaderboard.enabled)
        assertEquals("Submit to top up", c.card.submitLabel)
        assertEquals("{time} - {label} - {amount} - {status}", c.history.entryFormat)
    }

    @Test
    fun `reads per-form overrides and toggles`() {
        val c = BedrockFormsConfig(
            yaml(
                """
                enabled: true
                card:
                  enabled: false
                  title: "Nap the"
                  serial-label: "Seri"
                leaderboard:
                  metric-button: "Chi so: {metric}"
                  metric-labels:
                    AMOUNT: "Tien"
                    POINT: "Diem"
                  period-labels:
                    ALL: "Tat ca"
                """.trimIndent(),
            ),
        )
        assertFalse(c.card.enabled)
        assertEquals("Nap the", c.card.title)
        assertEquals("Seri", c.card.serialLabel)
        assertEquals("Chi so: {metric}", c.leaderboard.metricButton)
        assertEquals("Tien", c.leaderboard.metricLabels["AMOUNT"])
        assertEquals("Diem", c.leaderboard.metricLabels["POINT"])
        assertEquals("Tat ca", c.leaderboard.periodLabels["ALL"])
    }

    @Test
    fun `master toggle can be disabled`() {
        assertFalse(BedrockFormsConfig(yaml("enabled: false")).enabled)
    }
}
