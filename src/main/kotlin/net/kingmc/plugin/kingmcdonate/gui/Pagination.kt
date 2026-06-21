package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Fills a [Gui]'s content slots from a list of [T] using a caller-supplied renderer,
 * leaving any static (non-content) items untouched. Navigation is driven by [next]/
 * [previous] (wired to `page:next`/`page:prev` actions or nav buttons). Data may be
 * supplied synchronously via [setItems] or loaded off-thread via [loadAsync], which
 * shows a loading placeholder and refills on the player's region thread only while the
 * menu is still open.
 */
class Pagination<T>(
    private val gui: Gui,
    private val contentSlots: List<Int>,
    private val render: (T) -> MenuItem,
) {

    private var items: List<T> = emptyList()
    private var page = 0

    val pageCount: Int get() = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    val pageIndex: Int get() = page
    private val pageSize: Int get() = contentSlots.size.coerceAtLeast(1)

    fun setItems(list: List<T>) {
        items = list
        page = 0
        draw()
    }

    /** Show [loading] in the content slots, fetch via [loader] off-thread, then refill if [player] still has the menu open. */
    fun loadAsync(scheduler: Scheduler, player: Player, loading: ItemStack, loader: () -> List<T>) {
        for (slot in contentSlots) gui.setItem(slot, loading)
        scheduler.runIo {
            val data = runCatching(loader).getOrDefault(emptyList())
            scheduler.runAtEntity(player) {
                if (isViewing(player)) setItems(data)
            }
        }
    }

    fun next() {
        if (page + 1 < pageCount) {
            page++
            draw()
        }
    }

    fun previous() {
        if (page > 0) {
            page--
            draw()
        }
    }

    private fun draw() {
        val start = page * pageSize
        contentSlots.forEachIndexed { i, slot ->
            val index = start + i
            if (index < items.size) gui.set(slot, render(items[index])) else gui.clear(slot)
        }
    }

    private fun isViewing(player: Player): Boolean = player.openInventory.topInventory.holder === gui
}
