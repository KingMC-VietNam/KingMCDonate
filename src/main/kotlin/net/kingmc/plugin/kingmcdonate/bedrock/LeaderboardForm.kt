package net.kingmc.plugin.kingmcdonate.bedrock

import net.kingmc.plugin.kingmcdonate.config.BedrockFormsConfig
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardView
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm

/**
 * Bedrock leaderboard form: the top-donor list as a SimpleForm with buttons to toggle metric
 * (money/point) and period (all/day/week/month), mirroring the Java menu's in-place toggles.
 * Button `0` toggles metric, `1` toggles period (each re-reads the board and re-sends the form),
 * `2` closes. The board is read off the main thread and the form sent on the region thread.
 */
class LeaderboardForm(
    private val leaderboard: LeaderboardService,
    private val forms: BedrockForms,
    private val scheduler: Scheduler,
    private val formConfig: () -> BedrockFormsConfig,
) {

    /** Returns true when this player is handled by the form; the actual send happens after an async read. */
    fun trySend(player: Player): Boolean {
        val fc = formConfig()
        if (!FormGate.shouldUse(fc.enabled, fc.leaderboard.enabled, forms.isAvailable, forms.isBedrock(player))) return false
        openWith(player, LeaderboardView.DEFAULT)
        return true
    }

    private fun openWith(player: Player, view: LeaderboardView) {
        scheduler.runIo {
            val entries = leaderboard.topEager(view.metric, view.period)
            scheduler.runAtEntity(player) { send(player, view, entries) }
        }
    }

    private fun send(player: Player, view: LeaderboardView, entries: List<LeaderboardDao.Entry>) {
        val lb = formConfig().leaderboard
        val metricLabel = lb.metricLabels[view.metric.name] ?: view.metric.name
        val periodLabel = lb.periodLabels[view.period.name] ?: view.period.name
        val list = if (entries.isEmpty()) {
            lb.empty
        } else {
            entries.mapIndexed { i, e -> formatRow(lb.entryFormat, i + 1, e, view) }.joinToString("\n")
        }
        val header = lb.header.replace("{metric}", metricLabel).replace("{period}", periodLabel)
        val content = if (header.isBlank()) list else "$header\n\n$list"
        val form = SimpleForm.builder()
            .title(lb.title)
            .content(content)
            .button(lb.metricButton.replace("{metric}", metricLabel))
            .button(lb.periodButton.replace("{period}", periodLabel))
            .button(lb.close)
            .validResultHandler { response ->
                when (response.clickedButtonId()) {
                    METRIC_BUTTON -> openWith(player, view.toggledMetric())
                    PERIOD_BUTTON -> openWith(player, view.toggledPeriod())
                    // button 2 is close: nothing to do
                }
            }
            .build()
        forms.send(player, form)
    }

    private fun formatRow(template: String, rank: Int, entry: LeaderboardDao.Entry, view: LeaderboardView): String =
        template
            .replace("{rank}", rank.toString())
            .replace("{name}", entry.name ?: "?")
            .replace("{value}", view.formatValue(entry.value))

    companion object {
        private const val METRIC_BUTTON = 0
        private const val PERIOD_BUTTON = 1
    }
}
