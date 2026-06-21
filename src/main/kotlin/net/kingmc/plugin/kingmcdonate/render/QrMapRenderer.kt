package net.kingmc.plugin.kingmcdonate.render

import org.bukkit.entity.Player

/**
 * Renders a payment QR onto a virtual map for a single player. `mapBytes` is a
 * 128x128 Minecraft map colour array. Implementations send the map purely by
 * packet (no real inventory write), so a fork can swap in an NMS renderer without
 * touching the payment core. Closing an inventory re-syncs the real slot, so the
 * payment flow calls [resend] to keep the QR visible.
 */
interface QrMapRenderer {

    /** Show [mapBytes] as a virtual filled-map in the player's held slot. */
    fun show(player: Player, mapBytes: ByteArray)

    /** Re-send the active QR (after an inventory close re-synced the slot). No-op when none is active. */
    fun resend(player: Player)

    /** Remove the QR and restore the player's real slot. No-op when none is active. */
    fun clear(player: Player)
}
