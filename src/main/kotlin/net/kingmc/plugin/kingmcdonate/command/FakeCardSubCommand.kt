package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.payment.card.CardPaymentService
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

/** `/kingmcdonate fakecard <player> <amount>` — run the success path without a gateway. */
class FakeCardSubCommand(
    private val service: CardPaymentService,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) : SubCommand {

    override val name = "fakecard"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        if (args.size < 2) {
            messages().send(sender, MessageKeys.FAKECARD_USAGE)
            return
        }
        val target = Bukkit.getPlayerExact(args[0])
        if (target == null) {
            messages().send(sender, MessageKeys.FAKECARD_PLAYER_NOT_FOUND, "player" to args[0])
            return
        }
        val amount = args[1].toLongOrNull()
        if (amount == null) {
            messages().send(sender, MessageKeys.FAKECARD_USAGE)
            return
        }
        if (amount !in config().card.denominations) {
            messages().send(sender, MessageKeys.CARD_INVALID_DENOMINATION)
            return
        }
        service.simulateSuccess(target.uniqueId, target.name, amount)
        messages().send(
            sender,
            MessageKeys.FAKECARD_DONE,
            "player" to target.name,
            "amount" to Text.formatMoney(amount),
        )
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> = when (args.size) {
        1 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        2 -> config().card.denominations.keys.map { it.toString() }.filter { it.startsWith(args[1]) }
        else -> emptyList()
    }
}
