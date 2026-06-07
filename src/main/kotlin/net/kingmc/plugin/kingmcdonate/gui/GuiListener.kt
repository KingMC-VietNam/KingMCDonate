package net.kingmc.plugin.kingmcdonate.gui

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

/**
 * Single shared listener for every [Gui]. Routing is by inventory holder, so one
 * registration handles all GUIs and there is nothing to unregister per-player.
 * Clicks and drags on a GUI are always cancelled (items can't be taken); a click
 * is then dispatched to the GUI's per-slot handler.
 */
class GuiListener : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder is Gui) {
            event.isCancelled = true
            holder.handleClick(event)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is Gui) {
            event.isCancelled = true
        }
    }
}
