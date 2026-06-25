package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class WebhookRouterTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    private class CapturingHandler(override val providerKey: String) : WebhookHandler {
        var seen: WebhookRequest? = null
        override fun handle(request: WebhookRequest): WebhookResponse {
            seen = request
            return WebhookResponse.ok("handled:$providerKey")
        }
    }

    private fun request(path: String, body: ByteArray = ByteArray(0)) =
        WebhookRequest("POST", path, emptyMap(), emptyMap(), body)

    @Test
    fun `routes to the matching handler by provider key`() {
        val sepay = CapturingHandler("sepay")
        val card2k = CapturingHandler("card2k")
        val router = WebhookRouter("/kmd", listOf(sepay, card2k), logger)

        val response = router.route(request("/kmd/sepay"))

        assertEquals(200, response.status)
        assertEquals("handled:sepay", response.body)
        assertEquals("/kmd/sepay", sepay.seen?.path)
        assertEquals(null, card2k.seen)
    }

    @Test
    fun `trailing slash still matches`() {
        val sepay = CapturingHandler("sepay")
        val router = WebhookRouter("/kmd", listOf(sepay), logger)
        assertEquals(200, router.route(request("/kmd/sepay/")).status)
    }

    @Test
    fun `unknown path returns 404 and reaches no handler`() {
        val sepay = CapturingHandler("sepay")
        val router = WebhookRouter("/kmd", listOf(sepay), logger)

        val response = router.route(request("/kmd/unknown"))

        assertEquals(404, response.status)
        assertEquals(null, sepay.seen)
    }

    @Test
    fun `raw body bytes are preserved exactly through the request`() {
        val captured = CapturingHandler("sepay")
        val router = WebhookRouter("/kmd", listOf(captured), logger)
        val body = """{"id":1,"transferAmount":50000}""".toByteArray()

        router.route(request("/kmd/sepay", body))

        assertArrayEquals(body, captured.seen?.rawBody)
    }

    @Test
    fun `query is decoded into a map`() {
        val parsed = WebhookRequest.parseQuery("status=1&message=Th%C3%A0nh%20c%C3%B4ng&request_id=98")
        assertEquals("1", parsed["status"])
        assertEquals("Thành công", parsed["message"])
        assertEquals("98", parsed["request_id"])
    }
}
