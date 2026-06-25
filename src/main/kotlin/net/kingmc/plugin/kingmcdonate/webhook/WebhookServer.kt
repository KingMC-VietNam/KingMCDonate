package net.kingmc.plugin.kingmcdonate.webhook

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * The single embedded HTTP receiver for every gateway callback. Built on the JDK's
 * own HTTP server (no extra dependency) with a virtual-thread executor, so handler
 * work — including blocking database calls — runs off the server's accept thread and
 * off the Bukkit main thread. One instance binds one port and serves all routes.
 */
class WebhookServer(private val logger: PluginLogger) {

    private var server: HttpServer? = null

    /** The actually-bound port (useful when started on port 0), or null when not running. */
    val boundPort: Int? get() = server?.address?.port

    /** Bind [host]:[port] and serve [router]. Throws if the port cannot be bound. */
    fun start(host: String, port: Int, router: WebhookRouter) {
        val httpServer = HttpServer.create(InetSocketAddress(host, port), BACKLOG)
        httpServer.executor = Executors.newVirtualThreadPerTaskExecutor()
        httpServer.createContext("/") { exchange -> dispatch(exchange, router) }
        httpServer.start()
        server = httpServer
        logger.info("Webhook server listening on $host:$port; routes=${router.routes}")
    }

    private fun dispatch(exchange: HttpExchange, router: WebhookRouter) {
        try {
            // Webhook payloads are tiny (a JSON object or a query-string GET); cap the body so the
            // exposed port can't be used to exhaust heap with a huge upload. Read at most one byte over
            // the cap to detect (and reject) an oversized body even when Content-Length is absent/lies.
            val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_BODY_BYTES) {
                write(exchange, WebhookResponse(413, "text/plain", "payload too large"))
                return
            }
            val rawBody = exchange.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
            if (rawBody.size > MAX_BODY_BYTES) {
                logger.warn("Webhook body exceeded $MAX_BODY_BYTES bytes; rejected.")
                write(exchange, WebhookResponse(413, "text/plain", "payload too large"))
                return
            }
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k to (v.firstOrNull() ?: "") }
            val request = WebhookRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = WebhookRequest.parseQuery(exchange.requestURI.rawQuery),
                headers = headers,
                rawBody = rawBody,
            )
            val response = router.route(request)
            logger.debug { "Webhook ${request.method} ${request.path} -> ${response.status}" }
            write(exchange, response)
        } catch (e: Exception) {
            logger.error("Webhook request handling failed.", e)
            runCatching { write(exchange, WebhookResponse(500, "text/plain", "error")) }
        } finally {
            exchange.close()
        }
    }

    private fun write(exchange: HttpExchange, response: WebhookResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", response.contentType)
        // A zero length tells the JDK server to send a no-body response with no Content-Length.
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    /** Stop accepting requests and release the port. Safe to call when never started. */
    fun stop() {
        server?.let {
            it.stop(0)
            logger.info("Webhook server stopped.")
        }
        server = null
    }

    companion object {
        private const val BACKLOG = 0
        private const val MAX_BODY_BYTES = 64 * 1024 // 64 KB; webhook payloads are far smaller
    }
}
