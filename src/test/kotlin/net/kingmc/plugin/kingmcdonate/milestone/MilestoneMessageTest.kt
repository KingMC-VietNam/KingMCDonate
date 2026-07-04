package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.payment.Donation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class MilestoneMessageTest {

    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `maps threshold and current distinctly from the donation`() {
        val d = Donation(uuid, "Alice", "bank", 200_000, 240, "REF9", MessageKeys.BANK_SUCCESS, "sepay")
        val vars = MilestoneService.messageVars("Alice", threshold = 100_000, current = 250_000, d = d)
        assertEquals("Alice", vars["player"])
        assertEquals("100000", vars["threshold"])
        assertEquals("250000", vars["current"])
        assertEquals("200000", vars["amount"])
        assertEquals("240", vars["point"])
        assertEquals("REF9", vars["ref"])
    }

    @Test
    fun `null donation yields safe defaults for donation-scoped tokens`() {
        val vars = MilestoneService.messageVars("Bob", threshold = 100_000, current = 100_000, d = null)
        assertEquals("Bob", vars["player"])
        assertEquals("100000", vars["threshold"])
        assertEquals("100000", vars["current"])
        assertEquals("0", vars["amount"])
        assertEquals("0", vars["point"])
        assertEquals("", vars["ref"])
    }
}
