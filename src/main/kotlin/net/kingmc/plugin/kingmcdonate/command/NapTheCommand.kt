package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.gui.CardTopupMenu
import net.kingmc.plugin.kingmcdonate.payment.CardPaymentService
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/napthe` — opens the top-up GUI with no arguments, or charges directly with
 * `<type> <amount> <serial> <pin>`. Tab completion offers enabled card types and
 * configured denominations.
 */
class NapTheCommand(
    private val service: CardPaymentService,
    private val menu: CardTopupMenu,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            messages().send(sender, MessageKeys.PLAYER_ONLY)
            return true
        }
        if (args.size != QUICK_ARGS) {
            if (config().card.maintenance) {
                messages().send(sender, MessageKeys.CARD_MAINTENANCE)
                return true
            }
            menu.openTypeMenu(sender)
            return true
        }

        val type = CardType.parse(args[0])
        if (type == null) {
            messages().send(sender, MessageKeys.CARD_INVALID_TYPE)
            return true
        }
        val amount = args[1].toLongOrNull()
        if (amount == null) {
            messages().send(sender, MessageKeys.CARD_INVALID_DENOMINATION)
            return true
        }
        service.submit(sender, type, amount, args[2], args[3])
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> = when (args.size) {
        1 -> config().card.cardTypes
            .map { it.lowercase() }
            .filter { it.startsWith(args[0].lowercase()) }
        2 -> config().card.denominations.keys.sorted()
            .map { it.toString() }
            .filter { it.startsWith(args[1]) }
        else -> emptyList()
    }

    companion object {
        private const val QUICK_ARGS = 4
    }
}
