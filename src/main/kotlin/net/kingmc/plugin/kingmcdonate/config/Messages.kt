package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection

/**
 * Typed, colorized view of `messages.yml`. A missing key never throws: it logs a
 * warning and returns the key itself as a visible placeholder. `{name}` tokens in
 * a message are replaced from the supplied vars before colorizing.
 */
class Messages(
    section: ConfigurationSection?,
    private val prefix: String,
    private val logger: PluginLogger,
) {

    private val messages: Map<String, String> =
        section?.getKeys(false)?.associateWith { section.getString(it) ?: "" } ?: emptyMap()

    /** Colorized message for [key]; logs a warning and returns the key if missing. */
    fun get(key: String, vararg vars: Pair<String, String>): String {
        val raw = messages[key]
        if (raw == null) {
            logger.warn("Missing message key: $key")
            return key
        }
        return Text.colorize(applyVars(raw, vars))
    }

    /** Send a prefixed message to [sender]; blank messages are skipped. */
    fun send(sender: CommandSender, key: String, vararg vars: Pair<String, String>) {
        val raw = messages[key]
        if (raw == null) {
            logger.warn("Missing message key: $key")
            sender.sendMessage(key)
            return
        }
        if (raw.isBlank()) return
        sender.sendMessage(Text.colorize(prefix + applyVars(raw, vars)))
    }

    private fun applyVars(raw: String, vars: Array<out Pair<String, String>>): String {
        if (vars.isEmpty()) return raw
        var result = raw
        for ((token, value) in vars) result = result.replace("{$token}", value)
        return result
    }
}
