package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.hook.PlaceholderApiHook
import org.bukkit.entity.Player

/**
 * Resolves placeholders in menu text. Internal `{token}` substitution always runs and
 * is pure (unit-tested without Bukkit); `%placeholder%` expansion is delegated to the
 * shared [PlaceholderApiHook] and only happens when PlaceholderAPI is installed.
 */
object Placeholders {

    /** Substitute `{token}` from [tokens] only — pure, no Bukkit. */
    fun applyTokens(text: String, tokens: Map<String, String>): String {
        if (tokens.isEmpty() || '{' !in text) return text
        var result = text
        for ((key, value) in tokens) result = result.replace("{$key}", value)
        return result
    }

    /** Full resolution: `{token}` then PlaceholderAPI `%...%` for [viewer] if installed. */
    fun apply(text: String, tokens: Map<String, String>, viewer: Player?): String =
        PlaceholderApiHook.apply(viewer, applyTokens(text, tokens))
}
