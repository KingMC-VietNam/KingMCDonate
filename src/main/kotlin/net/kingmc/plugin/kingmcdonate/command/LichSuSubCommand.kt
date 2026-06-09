package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/kingmcdonate lichsu [player]` — admin view of a player's card history. */
class LichSuSubCommand(
    private val cardPaymentDao: CardPaymentDao,
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
) : SubCommand {

    override val name = "lichsu"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            if (sender !is Player) {
                messages().send(sender, MessageKeys.PLAYER_ONLY)
                return
            }
            CardHistoryChat.show(sender, sender.uniqueId, cardPaymentDao, scheduler, messages())
            return
        }
        CardHistoryChat.showByName(sender, args[0], cardPaymentDao, scheduler, messages())
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size == 1) {
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        } else {
            emptyList()
        }
}
