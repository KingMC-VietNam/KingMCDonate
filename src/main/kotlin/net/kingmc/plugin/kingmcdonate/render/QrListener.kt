package net.kingmc.plugin.kingmcdonate.render

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Keeps the virtual QR map consistent: re-sends it after an inventory close (which
 * re-syncs the real slot and would wipe the fake map) and drops it when the player
 * quits. Both are node-local and touch no database.
 */
class QrListener(
    private val renderer: QrMapRenderer,
    private val scheduler: Scheduler,
) : Listener {

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        // Re-send next tick so the server's post-close slot sync completes first.
        scheduler.runAtEntity(player) { renderer.resend(player) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        scheduler.runAtEntity(player) { renderer.clear(player) }
    }
}
