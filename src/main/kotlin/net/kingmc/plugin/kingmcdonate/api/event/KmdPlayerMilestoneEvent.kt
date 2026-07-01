package net.kingmc.plugin.kingmcdonate.api.event

import net.kingmc.plugin.kingmcdonate.api.DonationPeriod
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired when a player crosses one of their donation milestone thresholds for a period.
 * Notification-only; the milestone reward is granted through the outbox before this fires.
 */
class KmdPlayerMilestoneEvent(
    val playerUuid: UUID,
    val playerName: String?,
    val threshold: Long,
    val period: DonationPeriod,
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
