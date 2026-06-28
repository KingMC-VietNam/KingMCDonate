package net.kingmc.plugin.kingmcdonate.discord

import org.bukkit.configuration.ConfigurationSection

/**
 * Typed view of `discord.yml`. Each event maps to a list of webhook hooks; an event
 * with no specific list falls back to `default`. A hook's `payload` is the raw Discord
 * webhook JSON (as nested maps/lists), preserved verbatim so operators can design it
 * freely; `%PLACEHOLDER%` tokens inside string values are substituted at send time.
 */
class DiscordConfig(section: ConfigurationSection?) {

    val enabled: Boolean = section?.getBoolean("enabled", false) ?: false

    /** Trailing serial/PIN characters left visible in card notifications; the rest is masked. */
    val serialVisibleChars: Int = (section?.getInt("serial-visible-chars", 3) ?: 3).coerceAtLeast(0)

    private val defaultHooks: List<Hook> = readHooks(section?.getConfigurationSection("default"))
    private val eventHooks: Map<String, List<Hook>> =
        section?.getConfigurationSection("events")?.let { events ->
            events.getKeys(false).associateWith { readHooks(events.getConfigurationSection(it)) }
        } ?: emptyMap()

    data class Hook(val enabled: Boolean, val url: String, val payload: Map<String, Any?>)

    /** Hooks for [event], or the default list when the event has no specific configuration. */
    fun hooksFor(event: String): List<Hook> = eventHooks[event] ?: defaultHooks

    /** Each child key of [section] is a hook name; an entry without a `url` is skipped. */
    private fun readHooks(section: ConfigurationSection?): List<Hook> =
        section?.getKeys(false).orEmpty().mapNotNull { name ->
            val hook = section?.getConfigurationSection(name) ?: return@mapNotNull null
            val url = hook.getString("url") ?: return@mapNotNull null
            val enabled = hook.getBoolean("enabled", true)
            val payload = hook.getConfigurationSection("payload")?.let { deepMap(it) } ?: emptyMap()
            Hook(enabled, url, payload)
        }

    /** Convert a section to nested maps, leaving list values (Discord JSON arrays) verbatim. */
    private fun deepMap(section: ConfigurationSection): Map<String, Any?> =
        section.getKeys(false).associateWith { key ->
            when (val value = section.get(key)) {
                is ConfigurationSection -> deepMap(value)
                else -> value
            }
        }

    companion object {
        const val EVENT_CARD = "card-success"
        const val EVENT_BANK = "bank-success"
        const val EVENT_PLAYER_MILESTONE = "player-milestone"
        const val EVENT_SERVER_MILESTONE = "server-milestone"
    }
}
