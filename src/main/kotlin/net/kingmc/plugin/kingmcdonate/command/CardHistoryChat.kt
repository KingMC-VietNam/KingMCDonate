package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.card.CardDisplay
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** Prints a player's recent card history to a command sender as a chat list. */
object CardHistoryChat {

    private const val LIMIT = 10

    /** Resolve [targetName] (online first, else an offline lookup off-thread) then show their history. */
    fun showByName(
        sender: CommandSender,
        targetName: String,
        cardPaymentDao: CardPaymentDao,
        scheduler: Scheduler,
        messages: Messages,
    ) {
        val online = Bukkit.getPlayerExact(targetName)
        if (online != null) {
            show(sender, online.uniqueId, cardPaymentDao, scheduler, messages)
            return
        }
        scheduler.runIo {
            @Suppress("DEPRECATION")
            val uuid = Bukkit.getOfflinePlayer(targetName).uniqueId
            show(sender, uuid, cardPaymentDao, scheduler, messages)
        }
    }

    fun show(
        sender: CommandSender,
        targetUuid: UUID,
        cardPaymentDao: CardPaymentDao,
        scheduler: Scheduler,
        messages: Messages,
    ) {
        scheduler.runIo {
            val payments = cardPaymentDao.findByPlayer(targetUuid, LIMIT)
            // Re-dispatch onto the player's region thread; console output stays on this thread.
            if (sender is Player) scheduler.runAtEntity(sender) { emit(sender, payments, messages) }
            else emit(sender, payments, messages)
        }
    }

    private fun emit(sender: CommandSender, payments: List<CardPayment>, messages: Messages) {
        messages.send(sender, MessageKeys.HISTORY_HEADER)
        if (payments.isEmpty()) {
            messages.send(sender, MessageKeys.HISTORY_EMPTY)
            return
        }
        for (payment in payments) {
            messages.send(
                sender,
                MessageKeys.HISTORY_ENTRY,
                "type" to payment.cardType,
                "amount" to Text.formatMoney(payment.amount),
                "status" to CardDisplay.statusText(payment.status, messages),
                "time" to CardDisplay.time(payment.createdAt),
            )
        }
    }
}
