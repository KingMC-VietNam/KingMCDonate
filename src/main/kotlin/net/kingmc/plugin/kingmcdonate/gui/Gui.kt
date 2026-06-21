package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Chest GUI. The GUI *is* its inventory's [InventoryHolder], so [GuiManager] can
 * route clicks back to it without a separate open-GUI registry. Each GUI carries the
 * load [token] it was created under; the manager closes (instead of dispatching) a
 * GUI whose token is stale after a reload. Per-slot handlers run after the click is
 * cancelled, so items can never be removed. Created via [GuiManager.create].
 */
class Gui internal constructor(
    title: String,
    val rows: Int,
    val token: Any,
    private val scheduler: Scheduler,
) : InventoryHolder {

    private val inventory: Inventory = Bukkit.createInventory(this, rows * 9, Text.colorize(title))
    private val items = HashMap<Int, MenuItem>()

    /** Place a [MenuItem] (item + optional handler) at [slot]. */
    fun set(slot: Int, menuItem: MenuItem) {
        if (slot !in 0 until inventory.size) return
        items[slot] = menuItem
        inventory.setItem(slot, menuItem.item)
    }

    /** Convenience: place [item] at [slot] with an optional [onClick] handler. */
    fun setItem(slot: Int, item: ItemStack, onClick: ((ClickContext) -> Unit)? = null) =
        set(slot, MenuItem(item, onClick))

    /** Clear a slot's item and handler. */
    fun clear(slot: Int) {
        items.remove(slot)
        inventory.setItem(slot, null)
    }

    /** Fill every currently-empty slot with an inert [item] (no handler). */
    fun fillEmpty(item: ItemStack) {
        for (slot in 0 until inventory.size) {
            if (items[slot] == null && inventory.getItem(slot) == null) {
                inventory.setItem(slot, item)
            }
        }
    }

    fun open(player: Player) {
        scheduler.runAtEntity(player) { player.openInventory(inventory) }
    }

    internal fun handleClick(context: ClickContext) {
        items[context.slot]?.onClick?.invoke(context)
    }

    override fun getInventory(): Inventory = inventory
}
