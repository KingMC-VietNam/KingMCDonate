package net.kingmc.plugin.kingmcdonate.api

import net.kingmc.plugin.kingmcdonate.api.event.KmdDonationFailedEvent
import net.kingmc.plugin.kingmcdonate.api.event.KmdDonationSuccessEvent
import net.kingmc.plugin.kingmcdonate.api.event.KmdPlayerMilestoneEvent
import net.kingmc.plugin.kingmcdonate.api.event.KmdServerMilestoneEvent
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import org.bukkit.event.Event
import java.util.UUID

/**
 * Publishes the plugin's Bukkit events. Callers run on virtual IO threads, so each
 * event is dispatched on the next tick (global region on Folia) where handlers can
 * safely touch the Bukkit API. [emit] is injectable so tests can capture events
 * without a running server.
 */
internal class KmdEventPublisher(
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val emit: (Event) -> Unit = { Bukkit.getPluginManager().callEvent(it) },
) {

    fun fireSuccess(d: Donation) = dispatch("success ref=${d.referenceCode} uuid=${d.uuid}") {
        KmdDonationSuccessEvent(d.uuid, d.name, d.method, d.amountVnd, d.point, d.referenceCode, d.provider)
    }

    fun fireFailed(uuid: UUID, method: String, amountVnd: Long, referenceCode: String, reason: String) =
        dispatch("failed ref=$referenceCode uuid=$uuid reason=$reason") {
            KmdDonationFailedEvent(uuid, method, amountVnd, referenceCode, reason)
        }

    fun firePlayerMilestone(d: Donation, threshold: Long, period: Period) =
        dispatch("player-milestone uuid=${d.uuid} threshold=$threshold period=$period") {
            KmdPlayerMilestoneEvent(d.uuid, d.name, threshold, period.toPublic())
        }

    fun fireServerMilestone(d: Donation, threshold: Long, period: Period) =
        dispatch("server-milestone threshold=$threshold period=$period by=${d.uuid}") {
            KmdServerMilestoneEvent(threshold, period.toPublic(), d.uuid, d.name)
        }

    private fun dispatch(what: String, build: () -> Event) {
        scheduler.runNextTick {
            logger.debug { "Firing KMD event: $what" }
            emit(build())
        }
    }

    private fun Period.toPublic(): DonationPeriod = when (this) {
        Period.ALL -> DonationPeriod.ALL
        Period.DAY -> DonationPeriod.DAY
        Period.WEEK -> DonationPeriod.WEEK
        Period.MONTH -> DonationPeriod.MONTH
    }
}
