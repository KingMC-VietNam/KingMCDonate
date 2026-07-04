package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class MessagesTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    private fun messages(yaml: String): Messages {
        val cfg = YamlConfiguration()
        cfg.loadFromString(yaml.trimIndent())
        return Messages(cfg.getConfigurationSection("messages"), prefix = "", logger = logger)
    }

    @Test
    fun `substitutes named tokens`() {
        val m = messages(
            """
            messages:
              greet: "Xin chao {player}, +{amount}"
            """,
        )
        assertEquals("Xin chao Alice, +1000", m.get("greet", "player" to "Alice", "amount" to "1000"))
    }

    @Test
    fun `colorizes ampersand codes`() {
        val m = messages(
            """
            messages:
              ok: "&aDone"
            """,
        )
        assertEquals("§aDone", m.get("ok"))
    }

    @Test
    fun `missing key returns the key itself without throwing`() {
        val m = messages(
            """
            messages:
              ok: "hi"
            """,
        )
        assertEquals("khong-ton-tai", m.get("khong-ton-tai"))
    }

    @Test
    fun `unreplaced tokens are left intact`() {
        val m = messages(
            """
            messages:
              greet: "Hi {player} {missing}"
            """,
        )
        assertEquals("Hi Bob {missing}", m.get("greet", "player" to "Bob"))
    }
}
