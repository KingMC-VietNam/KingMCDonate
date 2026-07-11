package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginConfigEngagementTest {

    private fun config(yaml: String): PluginConfig {
        val y = YamlConfiguration()
        y.loadFromString(yaml)
        return PluginConfig(y)
    }

    @Test
    fun `bank prefix defaults to KMD`() {
        assertEquals("KMD", config("prefix: \"\"\n").bank.prefix)
    }

    @Test
    fun `bank prefix is uppercased and keeps single spaces`() {
        assertEquals("UNG HO TT", config("bank:\n  prefix: \"ung ho tt\"\n").bank.prefix)
    }

    @Test
    fun `bank prefix strips diacritics and special characters`() {
        // "Nạp!" -> uppercase "NẠP!" -> drop everything outside [A-Z0-9 ] -> "NP"
        assertEquals("NP", config("bank:\n  prefix: \"Nạp!\"\n").bank.prefix)
    }

    @Test
    fun `bank prefix collapses and trims whitespace`() {
        assertEquals("A B", config("bank:\n  prefix: \"  A   B  \"\n").bank.prefix)
    }

    @Test
    fun `bank prefix is capped at eleven characters`() {
        assertEquals("ABCDEFGHIJK", config("bank:\n  prefix: \"ABCDEFGHIJKLMNOP\"\n").bank.prefix)
    }

    @Test
    fun `defaults are applied when sections are absent`() {
        val c = config("prefix: \"\"\n")
        assertEquals("point", c.pointUnit)
        assertFalse(c.broadcast.onSuccess)
        assertFalse(c.firstTopup.enabled)
        assertTrue(c.bossbar.enabled)
        assertEquals(60L, c.leaderboard.cacheTtlSeconds)
        assertEquals(10, c.leaderboard.size)
    }

    @Test
    fun `values are read from sections`() {
        val c = config(
            """
            point-unit: "xu"
            broadcast:
              on-success: true
              format: "&a{player} nạp {amount}"
            first-topup:
              enabled: true
              commands:
                - "console: give {player} diamond 1"
            bossbar:
              enabled: false
              update-interval: 40
              cycle-interval: 8
            leaderboard:
              cache-ttl-seconds: 30
              size: 25
            """.trimIndent(),
        )
        assertEquals("xu", c.pointUnit)
        assertTrue(c.broadcast.onSuccess)
        assertEquals("&a{player} nạp {amount}", c.broadcast.format)
        assertTrue(c.firstTopup.enabled)
        assertEquals(listOf("console: give {player} diamond 1"), c.firstTopup.commands)
        assertFalse(c.bossbar.enabled)
        assertEquals(40L, c.bossbar.updateIntervalTicks)
        assertEquals(8L, c.bossbar.cycleIntervalSeconds)
        assertEquals(30L, c.leaderboard.cacheTtlSeconds)
        assertEquals(25, c.leaderboard.size)
    }
}
