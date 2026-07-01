package net.kingmc.plugin.kingmcdonate.api.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired once after a donation is confirmed and its reward applied. Notification-only:
 * the reward is already granted idempotently before this fires, so it is not cancellable.
 */
class KmdDonationSuccessEvent(
    val playerUuid: UUID,
    val playerName: String?,
    val method: String,
    val amountVnd: Long,
    val point: Long,
    val referenceCode: String,
    val provider: String,
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
