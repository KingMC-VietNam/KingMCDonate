package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * Startup warnings for a bank webhook's authentication scheme. Shared by the handlers so the
 * two cannot drift in wording. Emitted once when a handler is constructed — which happens only
 * where the webhook is actually registered — never per request.
 */
object BankWebhookAuth {

    fun warnOnStartup(auth: String, provider: String, logger: PluginLogger) {
        when {
            auth.isBlank() -> logger.warn(
                "webhook-auth is not set for '$provider': every webhook confirmation will be REJECTED " +
                    "until you set a scheme (hmac/bearer/apikey), or 'none' to disable authentication (insecure).",
            )
            auth.equals("none", ignoreCase = true) -> logger.warn(
                "webhook-auth='none' for '$provider': authentication is DISABLED and forged confirmations " +
                    "are possible — test-only, never use this on a live server.",
            )
        }
    }
}
