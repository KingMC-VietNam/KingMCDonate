package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.gui.screen.HistoryMenu
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/lichsunap` — opens a player's own card history GUI; `/lichsunap <player>` prints
 * another player's history as a chat list (admin only).
 */
class LichSuNapCommand(
    private val cardPaymentDao: CardPaymentDao,
    private val historyMenu: HistoryMenu,
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender !is Player) {
                messages().send(sender, MessageKeys.PLAYER_ONLY)
                return true
            }
            historyMenu.open(sender)
            return true
        }

        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages().send(sender, MessageKeys.NO_PERMISSION)
            return true
        }
        CardHistoryChat.showByName(sender, args[0], cardPaymentDao, scheduler, messages())
        return true
    }

    companion object {
        private const val ADMIN_PERMISSION = "kingmcdonate.admin"
    }
}
