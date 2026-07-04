package net.kingmc.plugin.kingmcdonate.gui.screen

import net.kingmc.plugin.kingmcdonate.bedrock.HistoryForm
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.gui.MenuItem
import net.kingmc.plugin.kingmcdonate.gui.Pagination
import net.kingmc.plugin.kingmcdonate.gui.menu.ItemTemplate
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuService
import net.kingmc.plugin.kingmcdonate.payment.card.CardDisplay
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Read-only paginated view of a player's recent top-ups (card + bank, merged newest
 * first), rendered from `history.yml`. The frame and controls come from config; each
 * row is built from the `entry-item` template with the row's material/label/amount/
 * status/time. Rows are loaded off the main thread via the pagination async loader and
 * filled back on the player's region thread only while the menu is still open.
 */
class HistoryMenu(
    private val cardPaymentDao: CardPaymentDao,
    private val bankPaymentDao: BankPaymentDao,
    private val menus: MenuService,
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
    private val bedrockForm: HistoryForm? = null,
) {

    private data class Entry(
        val createdAt: Long,
        val material: String,
        val label: String,
        val amount: Long,
        val status: PaymentStatus,
    )

    init {
        menus.registerOpener("history") { open(it) }
    }

    fun open(player: Player) {
        if (bedrockForm?.trySend(player) == true) return
        val definition = menus.registry.get("history") ?: return
        val gui = menus.create(definition, player, menus.baseTokens(player))
        val entryItem = definition.root.getConfigurationSection("entry-item")
        val loading = definition.root.getConfigurationSection("loading-item")
            ?.let { ItemTemplate.fromConfig(it).build(emptyMap(), player) }
            ?: ItemBuilder.of("CLOCK").name("&7Loading...").build()

        val pagination = Pagination(gui, definition.contentSlots) { entry: Entry ->
            val tokens = mapOf(
                "material" to entry.material,
                "label" to entry.label,
                "amount" to Text.formatMoney(entry.amount),
                "status" to CardDisplay.statusText(entry.status, messages()),
                "time" to CardDisplay.time(entry.createdAt),
            )
            val item = if (entryItem != null) {
                ItemTemplate.fromConfig(entryItem).build(tokens, player)
            } else {
                ItemBuilder.of(entry.material).name("&f${entry.label}").build()
            }
            MenuItem(item)
        }
        menus.attachPagination(gui, pagination)
        gui.open(player)
        pagination.loadAsync(scheduler, player, loading) { loadEntries(player.uniqueId) }
    }

    private fun loadEntries(uuid: UUID): List<Entry> {
        val bankLabel = messages().get(MessageKeys.HISTORY_BANK_LABEL)
        val cards = cardPaymentDao.findByPlayer(uuid, MAX_ENTRIES).map {
            Entry(it.createdAt, "PAPER", it.cardType, it.amount, it.status)
        }
        val banks = bankPaymentDao.findByPlayer(uuid, MAX_ENTRIES).map {
            Entry(it.createdAt, "GOLD_INGOT", bankLabel, it.amount, it.status)
        }
        return (cards + banks).sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
    }

    companion object {
        private const val MAX_ENTRIES = 54
    }
}
