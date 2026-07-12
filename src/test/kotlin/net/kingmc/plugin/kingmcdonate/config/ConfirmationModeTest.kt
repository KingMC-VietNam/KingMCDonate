package net.kingmc.plugin.kingmcdonate.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmationModeTest {

    @Test
    fun `parse is case-insensitive and falls back to poll`() {
        assertEquals(ConfirmationMode.WEBHOOK, ConfirmationMode.parse("WeBhOoK"))
        assertEquals(ConfirmationMode.BOTH, ConfirmationMode.parse("both"))
        assertEquals(ConfirmationMode.PASSIVE, ConfirmationMode.parse("PaSsIvE"))
        assertEquals(ConfirmationMode.POLL, ConfirmationMode.parse("nonsense"))
        assertEquals(ConfirmationMode.POLL, ConfirmationMode.parse(null))
    }

    @Test
    fun `poll polls the gateway and uses no webhook`() {
        assertTrue(ConfirmationMode.POLL.pollsGateway)
        assertFalse(ConfirmationMode.POLL.usesWebhook)
    }

    @Test
    fun `webhook uses webhook and does not poll`() {
        assertFalse(ConfirmationMode.WEBHOOK.pollsGateway)
        assertTrue(ConfirmationMode.WEBHOOK.usesWebhook)
    }

    @Test
    fun `both polls and uses webhook`() {
        assertTrue(ConfirmationMode.BOTH.pollsGateway)
        assertTrue(ConfirmationMode.BOTH.usesWebhook)
    }

    @Test
    fun `passive neither polls nor uses webhook`() {
        assertFalse(ConfirmationMode.PASSIVE.pollsGateway)
        assertFalse(ConfirmationMode.PASSIVE.usesWebhook)
    }
}
