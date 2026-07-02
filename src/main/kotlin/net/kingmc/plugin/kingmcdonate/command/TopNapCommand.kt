package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.gui.screen.LeaderboardMenu
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/topnap` — open the leaderboard GUI for players; print the top to chat for the console. */
class TopNapCommand(
    private val leaderboard: LeaderboardService,
    private val menu: LeaderboardMenu,
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender is Player) {
            menu.open(sender)
            return true
        }
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
