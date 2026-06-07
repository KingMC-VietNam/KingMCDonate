package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Minimal chest GUI. The GUI *is* its inventory's [InventoryHolder], so
 * [GuiListener] can route clicks back to it without a separate open-GUI registry
 * (nothing to leak on close). Per-slot click handlers run after the event is
 * cancelled, so items can never be removed. Opening is done Folia-safely via the
 * shared scheduler.
 */
class Gui(title: String, val rows: Int) : InventoryHolder {

    private val inventory: Inventory = Bukkit.createInventory(this, rows * 9, Text.colorize(title))
    private val handlers = HashMap<Int, (InventoryClickEvent) -> Unit>()

    /** Place [item] at [slot]; [onClick] (if given) runs when that slot is clicked. */
    fun setItem(slot: Int, item: ItemStack, onClick: ((InventoryClickEvent) -> Unit)? = null) {
        inventory.setItem(slot, item)
        if (onClick != null) handlers[slot] = onClick else handlers.remove(slot)
    }

    fun open(player: Player) {
        KingMCDonateContext.scheduler.runAtEntity(player) { player.openInventory(inventory) }
    }

    internal fun handleClick(event: InventoryClickEvent) {
        handlers[event.rawSlot]?.invoke(event)
    }

    override fun getInventory(): Inventory = inventory
}
