package net.kingmc.plugin.kingmcdonate.leaderboard

import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao.Metric
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Text

/**
 * Pure state of the leaderboard menu: which metric and period it currently shows, plus
 * how to turn a ranked entry into the tokens the entry-item template expects. Kept free
 * of Bukkit so the toggle and formatting logic is unit-testable.
 */
data class LeaderboardView(val metric: Metric, val period: Period) {

    fun toggledMetric(): LeaderboardView =
        copy(metric = if (metric == Metric.AMOUNT) Metric.POINT else Metric.AMOUNT)

    fun toggledPeriod(): LeaderboardView {
        val order = Period.entries
        return copy(period = order[(order.indexOf(period) + 1) % order.size])
    }

    /** Format a metric value: money for AMOUNT, a plain number for POINT. */
    fun formatValue(value: Long): String =
        if (metric == Metric.AMOUNT) Text.formatMoney(value) else value.toString()

    /** Tokens for one ranked row, using the yml-supplied [metricLabel]. */
    fun rowTokens(rank: Int, name: String?, value: Long, metricLabel: String): Map<String, String> = mapOf(
        "rank" to rank.toString(),
        "player" to (name ?: "?"),
        "metric" to metricLabel,
        "value" to formatValue(value),
    )

    companion object {
        val DEFAULT = LeaderboardView(Metric.AMOUNT, Period.ALL)
    }
}
