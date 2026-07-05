package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.config.ConfirmationMode

/**
 * Pure resolution of the confirmation strategy, shared by bootstrap and the reload path so
 * the webhook server and the poller never disagree. [webhookEnabledForMode] decides whether a
 * webhook handler should exist; [gatewayQueryNeeded] decides whether the poller must still query
 * the gateway once we know whether webhook delivery is actually active. Keeping both here means a
 * reload re-derives the same decisions from live config instead of a value frozen at bootstrap.
 */
object WebhookActivation {

    /** A webhook handler is wanted only when the mode uses webhook and the global toggle is on. */
    fun webhookEnabledForMode(mode: ConfirmationMode, webhookEnabled: Boolean): Boolean =
        mode.usesWebhook && webhookEnabled

    /**
     * The poller must query the gateway when the mode polls (POLL/BOTH), or when webhook delivery
     * is not actually active (disabled, wrong mode, or the provider has no webhook support) — so a
     * missing callback never strands an order.
     */
    fun gatewayQueryNeeded(mode: ConfirmationMode, webhookActive: Boolean): Boolean =
        mode.pollsGateway || !webhookActive
}
