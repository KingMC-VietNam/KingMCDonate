package net.kingmc.plugin.kingmcdonate.gui

import com.cryptomorin.xseries.XSound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * Ergonomic wrapper passed to a [MenuItem]'s click handler. Exposes the clicking
 * player, the slot, the click type with helpers, and the common operations a menu
 * handler needs (cancel, close, play a sound) without touching the raw event.
 */
class ClickContext(val event: InventoryClickEvent, val gui: Gui) {

    val player: Player get() = event.whoClicked as Player
    val slot: Int get() = event.rawSlot
    val clickType get() = event.click

    val isLeftClick: Boolean get() = clickType.isLeftClick
    val isRightClick: Boolean get() = clickType.isRightClick
    val isShiftClick: Boolean get() = clickType.isShiftClick

    /** Already cancelled by the listener; kept for explicitness in handlers. */
    fun cancel() {
        event.isCancelled = true
    }

    fun close() {
        player.closeInventory()
    }

    /** Play [sound] (an XSeries sound name) to the clicking player; unknown names are ignored. */
    fun playSound(sound: String?, volume: Float = 1.0f, pitch: Float = 1.0f) {
        if (sound.isNullOrBlank()) return
        XSound.matchXSound(sound).ifPresent { it.play(player, volume, pitch) }
    }
}
