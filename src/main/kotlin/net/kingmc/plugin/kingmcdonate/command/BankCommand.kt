package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.payment.bank.BankPaymentService
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/bank <amount>` — open a bank QR order for the given amount. */
class BankCommand(
    private val service: BankPaymentService,
    private val messages: () -> Messages,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            messages().send(sender, MessageKeys.PLAYER_ONLY)
            return true
        }
        val amount = args.firstOrNull()?.toLongOrNull()
        if (amount == null || amount <= 0) {
            messages().send(sender, MessageKeys.BANK_USAGE)
            return true
        }
        service.open(sender, amount)
        return true
    }
}
