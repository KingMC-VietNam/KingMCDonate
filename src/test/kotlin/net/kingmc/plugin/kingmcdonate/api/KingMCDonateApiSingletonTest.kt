package net.kingmc.plugin.kingmcdonate.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

class KingMCDonateApiSingletonTest {

    private val fake = object : KingMCDonateAPI {
        override fun getTotalVnd(uuid: UUID) = 0L
        override fun getPoint(uuid: UUID) = 0L
        override fun getTop(metric: DonationMetric, period: DonationPeriod) = emptyList<TopEntry>()
        override fun giveManual(uuid: UUID, method: String, amount: Long, point: Long?) {}
    }

    @AfterEach
    fun tearDown() = KingMCDonateAPI.set(null)

    @Test
    fun `get returns the set instance then null after clear`() {
        assertNull(KingMCDonateAPI.get())
        KingMCDonateAPI.set(fake)
        assertSame(fake, KingMCDonateAPI.get())
        KingMCDonateAPI.set(null)
        assertNull(KingMCDonateAPI.get())
    }
}
