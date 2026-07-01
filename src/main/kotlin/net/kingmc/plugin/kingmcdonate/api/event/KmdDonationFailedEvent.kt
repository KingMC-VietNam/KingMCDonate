package net.kingmc.plugin.kingmcdonate.api.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired when a top-up order is closed as failed: a card charge the gateway rejected or
 * that timed out while waiting, or a bank order that expired. Notification-only.
 */
class KmdDonationFailedEvent(
    val playerUuid: UUID,
    val method: String,
    val amountVnd: Long,
    val referenceCode: String,
    val reason: String,
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
