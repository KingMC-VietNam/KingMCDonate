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
        val periodSection = section?.getConfigurationSection(period.name.lowercase()) ?: return emptyList()
        return periodSection.getKeys(false).mapNotNull { key ->
            val threshold = key.toLongOrNull() ?: return@mapNotNull null
            val entry = periodSection.getConfigurationSection(key) ?: return@mapNotNull null
            val commands = entry.getStringList("commands")
            val message = entry.getString("message")
            val bar = entry.getConfigurationSection("bossbar")
            val bossBar = BossBarSpec(
                enabled = bar?.getBoolean("enabled", false) ?: false,
                color = bar?.getString("color") ?: "GREEN",
                style = bar?.getString("style") ?: "SEGMENTED_10",
            )
            Entry(period, threshold, message, commands, bossBar)
        }.sortedBy { it.threshold }
    }
}
