package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.CardDisplay
import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player

/**
 * Read-only chest view of a player's recent top-ups (card + bank, merged newest
 * first). Rows are loaded off the main thread and the inventory is opened back on
 * the player's region thread.
 */
class HistoryMenu(
    private val cardPaymentDao: CardPaymentDao,
    private val bankPaymentDao: BankPaymentDao,
    private val scheduler: Scheduler,
) {

    private data class Entry(
        val createdAt: Long,
        val material: String,
        val title: String,
        val status: PaymentStatus,
    )

    fun open(player: Player) {
        scheduler.runIo {
            val cards = cardPaymentDao.findByPlayer(player.uniqueId, MAX_ENTRIES).map {
                Entry(it.createdAt, "PAPER", "&e${it.cardType} &7- &f${Text.formatMoney(it.amount)}", it.status)
            }
            val banks = bankPaymentDao.findByPlayer(player.uniqueId, MAX_ENTRIES).map {
                Entry(it.createdAt, "GOLD_INGOT", "&6Ngân hàng &7- &f${Text.formatMoney(it.amount)}", it.status)
            }
            val entries = (cards + banks).sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
            scheduler.runAtEntity(player) {
                val gui = Gui(TITLE, ROWS)
                entries.forEachIndexed { index, entry ->
                    val icon = ItemBuilder.of(entry.material)
                        .name(entry.title)
                        .lore(
                            "&7Trạng thái: ${CardDisplay.statusText(entry.status)}",
                            "&8${CardDisplay.time(entry.createdAt)}",
                        )
                        .build()
                    gui.setItem(index, icon)
                }
                gui.open(player)
            }
        }
    }

    companion object {
        private const val TITLE = "&8Lịch sử nạp"
        private const val ROWS = 6
        private const val MAX_ENTRIES = 54
    }
}
