package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * Dispatches a request to the handler whose route (`<base-path>/<providerKey>`)
 * matches the request path. An unmatched path returns 404 and reaches no handler.
 * Routes are fixed at construction from the registered handlers, so adding a gateway
 * adds a handler without changing the router.
 */
class WebhookRouter(
    basePath: String,
    handlers: List<WebhookHandler>,
    private val logger: PluginLogger,
) {

    private val byPath: Map<String, WebhookHandler> =
        handlers.associateBy { normalize("$basePath/${it.providerKey}") }

    val routes: Set<String> get() = byPath.keys

    fun route(request: WebhookRequest): WebhookResponse {
        val handler = byPath[normalize(request.path)]
        if (handler == null) {
            logger.debug { "Webhook 404: no handler for ${request.method} ${request.path}" }
            return WebhookResponse.notFound()
        }
        return handler.handle(request)
    }

    private fun normalize(path: String): String {
        val withLead = if (path.startsWith("/")) path else "/$path"
        return if (withLead.length > 1 && withLead.endsWith("/")) withLead.dropLast(1) else withLead
    }
}
