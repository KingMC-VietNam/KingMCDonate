package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

/** `/topnap` — show the all-time top donors by VND. */
class TopNapCommand(
    private val leaderboard: LeaderboardService,
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        scheduler.runIo {
            val top = leaderboard.top(LeaderboardDao.Metric.AMOUNT, Period.ALL)
            scheduler.runNextTick {
                if (top.isEmpty()) {
                    messages().send(sender, MessageKeys.TOPNAP_EMPTY)
                    return@runNextTick
                }
                messages().send(sender, MessageKeys.TOPNAP_HEADER)
                top.forEachIndexed { i, entry ->
                    messages().send(
                        sender, MessageKeys.TOPNAP_ENTRY,
                        "rank" to (i + 1).toString(),
                        "player" to (entry.name ?: "?"),
                        "value" to Text.formatMoney(entry.value),
                    )
                }
            }
        }
        return true
    }
}
