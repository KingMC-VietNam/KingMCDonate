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
    private val state = PageState(contentSlots.size)

    val pageCount: Int get() = state.pageCount
    val pageIndex: Int get() = state.pageIndex

    fun setItems(list: List<T>) {
        items = list
        state.reset(list.size)
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
        if (state.next()) draw()
    }

    fun previous() {
        if (state.previous()) draw()
    }

    private fun draw() {
        val start = state.startIndex()
        contentSlots.forEachIndexed { i, slot ->
            val index = start + i
            if (index < items.size) gui.set(slot, render(items[index])) else gui.clear(slot)
        }
    }

    private fun isViewing(player: Player): Boolean = player.openInventory.topInventory.holder === gui
}
