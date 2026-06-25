package net.kingmc.plugin.kingmcdonate.webhook

/**
 * Handles a gateway's inbound callbacks. The handler owns its own authentication,
 * payload parsing and response shape; the shared server only routes to it by
 * [providerKey]. Adding a gateway's callback support means a new handler — the
 * server and router never change.
 */
interface WebhookHandler {

    /** The gateway key this handler serves; the route is `<base-path>/<providerKey>`. */
    val providerKey: String

    /** Verify, parse and act on a request, returning the gateway-specific reply. */
    fun handle(request: WebhookRequest): WebhookResponse
}
