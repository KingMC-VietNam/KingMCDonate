package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.command.CommandSender

/**
 * `/kingmcdonate reconcile` — the operator's window on the two things the payment core
 * deliberately cannot fix by itself.
 *
 * With no argument it lists **lost credits**: SUCCESS orders that claimed the reward but have no
 * ledger row. The reward gate claims before it credits (at-most-once — never double-pay), so a
 * `give` failure or a crash in between leaves an order that looks paid and isn't. The missing
 * ledger row is the only trace, and this is how an operator finds it.
 *
 * `reconcile card` lists **stranded card orders**: open orders owned by another node. Polling is
 * owner-scoped, so a node that dies for good never sweeps its own; `reconcile card <ref>` hands one
 * to this node, whose next poll then resolves it against the gateway. The operator decides which
 * nodes are dead — a live sibling's orders are listed too.
 *
 * Read-only apart from that explicit re-own: it never credits. Fixing a lost credit is
 * `/kingmcdonate give`, deliberately, so a payout is always a decision someone made.
 */
class ReconcileSubCommand(
    private val cardPaymentDao: CardPaymentDao,
    private val bankPaymentDao: BankPaymentDao,
    private val scheduler: Scheduler,
    private val config: () -> PluginConfig,
) : SubCommand {

    override val name = "reconcile"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        when {
            args.isEmpty() -> scheduler.runIo { reportLostCredits(sender) }
            !args[0].equals("card", ignoreCase = true) -> send(sender, "&cDùng: /kingmcdonate reconcile [card [<ref>]]")
            args.size == 1 -> scheduler.runIo { reportStrandedCards(sender) }
            else -> scheduler.runIo { reownCard(sender, args[1]) }
        }
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size == 1 && "card".startsWith(args[0], ignoreCase = true)) listOf("card") else emptyList()

    private fun reportLostCredits(sender: CommandSender) {
        val cards = cardPaymentDao.findLostCredits(LIMIT)
        val banks = bankPaymentDao.findLostCredits(LIMIT)
        if (cards.isEmpty() && banks.isEmpty()) {
            send(sender, "&aKhông có giao dịch nào nghi mất điểm.")
            return
        }
        send(sender, "&e&lGiao dịch đã trừ tiền nhưng KHÔNG có dòng sổ điểm &7(nghi mất khi cộng):")
        cards.forEach { send(sender, "  &7[thẻ] &f${it.line()}") }
        banks.forEach { send(sender, "  &7[bank] &f${it.line()}") }
        send(sender, "&7Cộng bù bằng: &f/kingmcdonate give <card|bank> <player> <amount> [point]")
        send(
            sender,
            "&8Lưu ý: đơn resolve TRƯỚC bản cập nhật này không xét được (hồi đó sổ vẫn ghi dù cộng lỗi); " +
                "đơn mà chính lượt ghi sổ bị lỗi cũng hiện ở đây dù đã cộng thành công.",
        )
    }

    private fun reportStrandedCards(sender: CommandSender) {
        val serverId = config().serverId
        val stranded = cardPaymentDao.findResolvableOnOtherServers(serverId)
        if (stranded.isEmpty()) {
            send(sender, "&aKhông có đơn thẻ nào treo ở node khác.")
            return
        }
        send(sender, "&e&lĐơn thẻ đang mở, thuộc node khác &7(node còn sống sẽ tự xử lý — chỉ nhận nuôi đơn của node đã chết):")
        stranded.forEach { send(sender, "  &7[${it.ownerServer}] &f${it.referenceCode} &7${Text.formatMoney(it.amount)} ${it.status}") }
        send(sender, "&7Nhận về node này: &f/kingmcdonate reconcile card <ref>")
    }

    private fun reownCard(sender: CommandSender, referenceCode: String) {
        val serverId = config().serverId
        val order = cardPaymentDao.findByReference(referenceCode)
        if (order == null) {
            send(sender, "&cKhông tìm thấy đơn &f$referenceCode&c.")
            return
        }
        if (order.ownerServer == serverId) {
            send(sender, "&eĐơn &f$referenceCode&e đã thuộc node này rồi.")
            return
        }
        val rows = cardPaymentDao.reown(referenceCode, serverId, System.currentTimeMillis())
        if (rows != 1) {
            send(sender, "&eĐơn &f$referenceCode&e đã kết thúc (&f${order.status}&e), không cần nhận nuôi.")
            return
        }
        send(
            sender,
            "&aĐã nhận đơn &f$referenceCode&a về &f$serverId&a. Lượt poll tới sẽ kiểm tra lại với cổng thanh toán " +
                "&7(thẻ đã bị gạch thì cộng điểm, chưa gạch thì đóng FAILED).",
        )
    }

    private fun CardPayment.line() =
        "$referenceCode ${Text.formatMoney(amount)} ${point}pt $playerName @$ownerServer"

    private fun BankPayment.line() =
        "$referenceCode ${Text.formatMoney(amount)} ${point}pt $playerUuid @$ownerServer"

    private fun send(sender: CommandSender, line: String) = sender.sendMessage(Text.colorize(line))

    companion object {
        private const val LIMIT = 20
    }
}
