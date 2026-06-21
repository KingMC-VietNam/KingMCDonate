package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

/**
 * Single shared owner of every [Gui]: the click/drag listener, the GUI factory, and
 * the holder of the current process load token. Clicks and drags on a GUI are always
 * cancelled (items can't be taken). A click on a GUI created under a previous load
 * token (e.g. left open across a `/kingmcdonate reload`) is not dispatched — the stale
 * GUI is closed instead, so edited menu definitions never mis-route to old handlers.
 */
class GuiManager(private val scheduler: Scheduler) : Listener {

    @Volatile
    private var token: Any = Any()

    /** Create a GUI stamped with the current load token. */
    fun create(title: String, rows: Int): Gui = Gui(title, rows.coerceIn(1, 6), token, scheduler)

    /** Regenerate the load token so GUIs opened before now are treated as stale. Call on reload. */
    fun invalidate() {
        token = Any()
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val gui = event.inventory.holder as? Gui ?: return
        event.isCancelled = true
        if (gui.token !== token) {
            event.whoClicked.closeInventory()
            return
        }
        gui.handleClick(ClickContext(event, gui))
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is Gui) event.isCancelled = true
    }
}
