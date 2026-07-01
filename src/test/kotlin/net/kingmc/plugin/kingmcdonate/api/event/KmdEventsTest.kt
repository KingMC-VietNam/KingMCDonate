package net.kingmc.plugin.kingmcdonate.api.event

import net.kingmc.plugin.kingmcdonate.api.DonationPeriod
import org.bukkit.event.Cancellable
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

class KmdEventsTest {

    @Test
    fun `events are notification-only with a static handler list`() {
        val uuid = UUID.randomUUID()
        val events = listOf(
            KmdDonationSuccessEvent(uuid, "Alice", "card", 100_000, 1000, "REF", "card2k"),
            KmdDonationFailedEvent(uuid, "bank", 50_000, "REF2", "expired"),
            KmdPlayerMilestoneEvent(uuid, "Alice", 100_000, DonationPeriod.ALL),
            KmdServerMilestoneEvent(1_000_000, DonationPeriod.WEEK, uuid, "Alice"),
        )
        for (event in events) {
            assertFalse(event is Cancellable, "${event.javaClass.simpleName} must not be Cancellable")
            assertNotNull(event.handlers)
        }
        // The static and instance handler lists must be the same object (Bukkit registration contract).
        assertSame(KmdDonationSuccessEvent.getHandlerList(), events[0].handlers)
    }
}
