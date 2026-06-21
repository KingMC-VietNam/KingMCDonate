package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.payment.bank.BankPaymentService
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

/** `/kingmcdonate fakebank <player> <amount>` — run the bank confirm/reward path without a gateway. */
class FakeBankSubCommand(
    private val service: BankPaymentService,
    private val messages: () -> Messages,
) : SubCommand {

    override val name = "fakebank"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        if (args.size < 2) {
            messages().send(sender, MessageKeys.FAKEBANK_USAGE)
            return
        }
        val target = Bukkit.getOfflinePlayer(args[0])
        val name = target.name
        if (name == null || (!target.isOnline && !target.hasPlayedBefore())) {
            messages().send(sender, MessageKeys.FAKECARD_PLAYER_NOT_FOUND, "player" to args[0])
            return
        }
        val amount = args[1].toLongOrNull()
        if (amount == null || amount <= 0) {
            messages().send(sender, MessageKeys.FAKEBANK_USAGE)
            return
        }
        service.simulate(target.uniqueId, name, amount)
        messages().send(
            sender,
            MessageKeys.FAKEBANK_DONE,
            "player" to name,
            "amount" to Text.formatMoney(amount),
        )
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> = when (args.size) {
        1 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        else -> emptyList()
    }
}
