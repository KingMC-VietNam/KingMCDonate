package net.kingmc.plugin.kingmcdonate.api.event

import net.kingmc.plugin.kingmcdonate.api.DonationPeriod
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired when the whole server crosses a global donation milestone threshold for a period.
 * [triggeredByUuid]/[triggeredByName] identify the donor whose top-up crossed it.
 * Notification-only.
 */
class KmdServerMilestoneEvent(
    val threshold: Long,
    val period: DonationPeriod,
    val triggeredByUuid: UUID,
    val triggeredByName: String?,
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
