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

    private val defaultHooks: List<Hook> = readHooks(section?.getList("default"))
    private val eventHooks: Map<String, List<Hook>> =
        section?.getConfigurationSection("events")?.let { events ->
            events.getKeys(false).associateWith { readHooks(events.getList(it)) }
        } ?: emptyMap()

    data class Hook(val enabled: Boolean, val url: String, val payload: Map<String, Any?>)

    /** Hooks for [event], or the default list when the event has no specific configuration. */
    fun hooksFor(event: String): List<Hook> = eventHooks[event] ?: defaultHooks

    @Suppress("UNCHECKED_CAST")
    private fun readHooks(list: List<*>?): List<Hook> = list.orEmpty().mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        val url = map["url"]?.toString() ?: return@mapNotNull null
        val enabled = (map["enabled"] as? Boolean) ?: true
        val payload = (map["payload"] as? Map<*, *>)?.let { coerce(it) } ?: emptyMap()
        Hook(enabled, url, payload)
    }

    @Suppress("UNCHECKED_CAST")
    private fun coerce(map: Map<*, *>): Map<String, Any?> =
        map.entries.associate { (k, v) -> k.toString() to v }

    companion object {
        const val EVENT_CARD = "card-success"
        const val EVENT_BANK = "bank-success"
        const val EVENT_PLAYER_MILESTONE = "player-milestone"
        const val EVENT_SERVER_MILESTONE = "server-milestone"
    }
}
