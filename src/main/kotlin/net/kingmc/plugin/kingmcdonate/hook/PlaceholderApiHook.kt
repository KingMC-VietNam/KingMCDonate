package net.kingmc.plugin.kingmcdonate.hook

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Optional integration with PlaceholderAPI, resolved reflectively so it stays a
 * soft-depend (no compile dependency). [install] is called once at enable; afterwards
 * any feature can call [apply] to expand `%placeholder%` tokens for a player. When
 * PlaceholderAPI is absent the hook is inert and [apply] returns the text unchanged.
 *
 * Lives outside the GUI layer so messages, reward output, leaderboards, etc. can share
 * the same resolver.
 */
object PlaceholderApiHook {

    @Volatile
    private var resolver: ((Player, String) -> String)? = null

    val isAvailable: Boolean get() = resolver != null

    fun install(logger: PluginLogger) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return
        runCatching {
            val api = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
            val method = api.getMethod("setPlaceholders", Player::class.java, String::class.java)
            resolver = { player, text -> method.invoke(null, player, text) as String }
            logger.debug { "PlaceholderAPI hook installed." }
        }.onFailure { logger.warn("Không gắn được PlaceholderAPI: ${it.message}") }
    }

    /** Expand `%placeholder%` tokens for [player]; returns [text] unchanged when unavailable or no player. */
    fun apply(player: Player?, text: String): String {
        val active = resolver
        return if (player != null && active != null && '%' in text) active(player, text) else text
    }
}
