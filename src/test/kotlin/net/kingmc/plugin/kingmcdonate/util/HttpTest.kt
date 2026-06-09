package net.kingmc.plugin.kingmcdonate.util

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class HttpTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var server: HttpServer
    private val calls = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun start(handler: (Int) -> Pair<Int, String>) {
        server.createContext("/") { exchange ->
            val (code, body) = handler(calls.incrementAndGet())
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/"

    @Test
    fun `retries transient 5xx then succeeds`() {
        start { attempt -> if (attempt < 3) 503 to "busy" else 200 to "ok" }
        val http = Http(connectTimeoutSeconds = 2, requestTimeoutSeconds = 5, maxRetries = 5, logger = logger)
        assertEquals("ok", http.get(url()))
        assertEquals(3, calls.get())
    }

    @Test
    fun `gives up after the retry limit`() {
        start { 503 to "down" }
        val http = Http(connectTimeoutSeconds = 2, requestTimeoutSeconds = 5, maxRetries = 3, logger = logger)
        assertThrows(IOException::class.java) { http.get(url()) }
        assertEquals(3, calls.get())
    }

    @Test
    fun `does not retry a 4xx response`() {
        start { 404 to "missing" }
        val http = Http(connectTimeoutSeconds = 2, requestTimeoutSeconds = 5, maxRetries = 3, logger = logger)
        assertThrows(IOException::class.java) { http.get(url()) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `posts form body and returns response`() {
        start { 200 to "received" }
        val http = Http(connectTimeoutSeconds = 2, requestTimeoutSeconds = 5, maxRetries = 1, logger = logger)
        assertEquals("received", http.postForm(url(), mapOf("a" to "1", "b" to "x y")))
        assertTrue(calls.get() >= 1)
    }
}
