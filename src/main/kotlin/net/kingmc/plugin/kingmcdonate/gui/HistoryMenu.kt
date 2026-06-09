package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.CardDisplay
import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player

/**
 * Read-only chest view of a player's recent card top-ups. Rows are loaded off the
 * main thread and the inventory is opened back on the player's region thread.
 */
class HistoryMenu(
    private val cardPaymentDao: CardPaymentDao,
    private val scheduler: Scheduler,
) {

    fun open(player: Player) {
        scheduler.runIo {
            val payments = cardPaymentDao.findByPlayer(player.uniqueId, MAX_ENTRIES)
            scheduler.runAtEntity(player) {
                val gui = Gui(TITLE, ROWS)
                payments.forEachIndexed { index, payment ->
                    val icon = ItemBuilder.of("PAPER")
                        .name("&e${payment.cardType} &7- &f${Text.formatMoney(payment.amount)}")
                        .lore(
                            "&7Trạng thái: ${CardDisplay.statusText(payment.status)}",
                            "&8${CardDisplay.time(payment.createdAt)}",
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
