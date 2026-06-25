package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Logger

class WebhookServerTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private val server = WebhookServer(logger)
    private val client = HttpClient.newHttpClient()

    private val handler = object : WebhookHandler {
        override val providerKey = "test"
        override fun handle(request: WebhookRequest) = WebhookResponse.json("""{"success":true}""")
    }

    @BeforeEach
    fun start() {
        server.start("127.0.0.1", 0, WebhookRouter("/kmd", listOf(handler), logger))
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    private fun post(body: ByteArray): Int {
        val uri = URI.create("http://127.0.0.1:${server.boundPort}/kmd/test")
        val request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    @Test
    fun `a small body is accepted`() {
        assertEquals(200, post("""{"id":1}""".toByteArray()))
    }

    @Test
    fun `an oversized body is rejected with 413`() {
        assertEquals(413, post(ByteArray(64 * 1024 + 1)))
    }
}
