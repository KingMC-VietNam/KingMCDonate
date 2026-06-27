package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.util.Period
import org.bukkit.configuration.ConfigurationSection

/**
 * Typed view of a milestone file (`mocnap.yml` for players, `mocnaptong.yml` for
 * the server). Thresholds are VND face amounts. Entries are grouped by period and
 * sorted ascending so callers can find the next uncompleted milestone cheaply.
 */
class MilestoneConfig(section: ConfigurationSection?) {

    data class BossBarSpec(val enabled: Boolean, val color: String, val style: String)

    data class Entry(
        val period: Period,
        val threshold: Long,
        val message: String?,
        val commands: List<String>,
        val bossBar: BossBarSpec,
    )

    private val byPeriod: Map<Period, List<Entry>> =
        Period.entries.associateWith { period -> read(section, period) }

    val all: List<Entry> = byPeriod.values.flatten()

    fun forPeriod(period: Period): List<Entry> = byPeriod[period] ?: emptyList()

    private fun read(section: ConfigurationSection?, period: Period): List<Entry> {
        val key = period.name.lowercase()
        val raw = section?.getMapList(key).orEmpty()
        return raw.mapNotNull { map ->
            val threshold = (map["threshold"] as? Number)?.toLong() ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val commands = (map["commands"] as? List<*>)?.map { it.toString() } ?: emptyList()
            val message = map["message"]?.toString()
            val bar = map["bossbar"] as? Map<*, *>
            val bossBar = BossBarSpec(
                enabled = (bar?.get("enabled") as? Boolean) ?: false,
                color = bar?.get("color")?.toString() ?: "GREEN",
                style = bar?.get("style")?.toString() ?: "SEGMENTED_10",
            )
            Entry(period, threshold, message, commands, bossBar)
        }.sortedBy { it.threshold }
    }
}
