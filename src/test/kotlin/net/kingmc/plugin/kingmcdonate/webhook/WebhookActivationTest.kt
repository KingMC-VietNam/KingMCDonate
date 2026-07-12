package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.config.ConfirmationMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookActivationTest {

    @Test
    fun `webhook handler is wanted only when the mode uses webhook and the toggle is on`() {
        assertTrue(WebhookActivation.webhookEnabledForMode(ConfirmationMode.WEBHOOK, true))
        assertTrue(WebhookActivation.webhookEnabledForMode(ConfirmationMode.BOTH, true))
        assertFalse(WebhookActivation.webhookEnabledForMode(ConfirmationMode.WEBHOOK, false))
        // POLL never uses webhook regardless of the toggle.
        assertFalse(WebhookActivation.webhookEnabledForMode(ConfirmationMode.POLL, true))
    }

    @Test
    fun `a poll-capable mode always queries the gateway`() {
        assertTrue(WebhookActivation.gatewayQueryNeeded(ConfirmationMode.POLL, webhookActive = true))
        assertTrue(WebhookActivation.gatewayQueryNeeded(ConfirmationMode.BOTH, webhookActive = true))
    }

    @Test
    fun `webhook-only queries the gateway only when webhook delivery is not active`() {
        assertFalse(WebhookActivation.gatewayQueryNeeded(ConfirmationMode.WEBHOOK, webhookActive = true))
        assertTrue(WebhookActivation.gatewayQueryNeeded(ConfirmationMode.WEBHOOK, webhookActive = false))
    }

    @Test
    fun `passive never queries the gateway even when webhook is not active locally`() {
        // Passive deliberately forgoes the poll fallback: another node is the confirmer.
        assertFalse(WebhookActivation.gatewayQueryNeeded(ConfirmationMode.PASSIVE, webhookActive = false))
        assertFalse(WebhookActivation.gatewayQueryNeeded(ConfirmationMode.PASSIVE, webhookActive = true))
    }

    @Test
    fun `passive never wants a webhook handler`() {
        assertFalse(WebhookActivation.webhookEnabledForMode(ConfirmationMode.PASSIVE, true))
    }
}
