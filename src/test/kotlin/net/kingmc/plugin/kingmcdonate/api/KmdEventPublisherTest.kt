package net.kingmc.plugin.kingmcdonate.api

import net.kingmc.plugin.kingmcdonate.api.event.KmdDonationFailedEvent
import net.kingmc.plugin.kingmcdonate.api.event.KmdDonationSuccessEvent
import net.kingmc.plugin.kingmcdonate.api.event.KmdPlayerMilestoneEvent
import net.kingmc.plugin.kingmcdonate.api.event.KmdServerMilestoneEvent
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.TestSchedulers
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class KmdEventPublisherTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private val emitted = mutableListOf<Event>()
    private val publisher = KmdEventPublisher(TestSchedulers.direct(), logger) { emitted.add(it) }

    private fun donation(uuid: UUID) =
        Donation(uuid, "Alice", "card", 100_000, 1000, "REF1", MessageKeys.CARD_SUCCESS, "card2k")

    @Test
    fun `success event fires exactly once with all fields`() {
        val uuid = UUID.randomUUID()
        publisher.fireSuccess(donation(uuid))
        assertEquals(1, emitted.size)
        val e = emitted[0] as KmdDonationSuccessEvent
        assertEquals(uuid, e.playerUuid)
        assertEquals("Alice", e.playerName)
        assertEquals("card", e.method)
        assertEquals(100_000L, e.amountVnd)
        assertEquals(1000L, e.point)
        assertEquals("REF1", e.referenceCode)
        assertEquals("card2k", e.provider)
    }

    @Test
    fun `failed event carries method, amount, ref and reason`() {
        val uuid = UUID.randomUUID()
        publisher.fireFailed(uuid, "bank", 50_000, "REF2", "expired")
        val e = emitted.single() as KmdDonationFailedEvent
        assertEquals(uuid, e.playerUuid)
        assertEquals("bank", e.method)
        assertEquals(50_000L, e.amountVnd)
        assertEquals("REF2", e.referenceCode)
        assertEquals("expired", e.reason)
    }

    @Test
    fun `player milestone maps the internal period to the public enum`() {
        val uuid = UUID.randomUUID()
        publisher.firePlayerMilestone(donation(uuid), 200_000, Period.WEEK)
        val e = emitted.single() as KmdPlayerMilestoneEvent
        assertEquals(uuid, e.playerUuid)
        assertEquals(200_000L, e.threshold)
        assertEquals(DonationPeriod.WEEK, e.period)
    }

    @Test
    fun `server milestone carries threshold, period and trigger`() {
        val uuid = UUID.randomUUID()
        publisher.fireServerMilestone(donation(uuid), 1_000_000, Period.MONTH)
        val e = emitted.single() as KmdServerMilestoneEvent
        assertEquals(1_000_000L, e.threshold)
        assertEquals(DonationPeriod.MONTH, e.period)
        assertEquals(uuid, e.triggeredByUuid)
    }
}
