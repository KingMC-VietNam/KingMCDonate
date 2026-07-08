package net.kingmc.plugin.kingmcdonate.util

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Shared outbound HTTP client for gateway calls. Built on [java.net.http.HttpClient]
 * with a bounded connect timeout and per-request timeout; transient failures (IO
 * errors and 5xx responses) are retried a bounded number of times with backoff,
 * and HTTP 429 is retried honoring the `Retry-After` header.
 *
 * Calls block and MUST run on a virtual thread (via `Scheduler.runIo`), never on
 * the main server thread.
 */
class Http(
    private val connectTimeoutSeconds: Long,
    private val requestTimeoutSeconds: Long,
    private val maxRetries: Int,
    private val logger: PluginLogger,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build()

    fun get(url: String): String =
        execute(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** GET with extra request headers (e.g. a Bearer token); body decoded as text. */
    fun get(url: String, headers: Map<String, String>): String {
        val builder = baseRequest(url).GET()
        for ((k, v) in headers) builder.header(k, v)
        return execute(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** GET returning the raw response bytes — used for binary payloads such as a QR PNG. */
    fun getBytes(url: String): ByteArray =
        execute(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    /** JSON POST (e.g. a Discord webhook). Retries IO/5xx like other calls. */
    fun postJson(url: String, json: String): String =
        execute(
            baseRequest(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /**
     * Form POST. [retry] defaults true (safe for idempotent calls such as a status check);
     * pass false for a charge-creating POST so a lost response is never re-sent (which could
     * double-charge) — the caller reconciles such a request by polling instead.
     */
    fun postForm(url: String, params: Map<String, String>, retry: Boolean = true): String =
        execute(
            baseRequest(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(params)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
            retryable = retry,
        )

    private fun baseRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))

    /**
     * Send with retry/backoff: returns the body on 2xx, retries IO errors and 5xx
     * with linear backoff, retries 429 honoring `Retry-After`, and surfaces other
     * 4xx immediately.
     */
    private fun <T> execute(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, retryable: Boolean = true): T {
        val maxAttempts = if (retryable) maxRetries else 1
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            val response = try {
                client.send(request, handler)
            } catch (e: IOException) {
                lastError = e
                logger.debug { "HTTP error (attempt $attempt/$maxAttempts) for ${request.uri()}: ${e.message}" }
                if (attempt < maxAttempts) backoff(attempt)
                continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("HTTP request interrupted", e)
            }

            val code = response.statusCode()
            if (code in 200..299) return response.body()
            if (code == TOO_MANY_REQUESTS) {
                lastError = IOException("HTTP 429 from ${request.uri()}")
                logger.debug { "HTTP 429 rate-limited (attempt $attempt/$maxAttempts) for ${request.uri()}" }
                if (attempt < maxAttempts) sleepMillis(retryAfterMillis(response) ?: (BACKOFF_BASE_MILLIS * attempt))
                continue
            }
            // Other client errors are not retryable — surface them immediately.
            if (code < 500) throw IOException("HTTP $code from ${request.uri()}")

            lastError = IOException("HTTP $code from ${request.uri()}")
            logger.debug { "HTTP $code (attempt $attempt/$maxAttempts) for ${request.uri()}" }
            if (attempt < maxAttempts) backoff(attempt)
        }
        throw IOException("HTTP request to ${request.uri()} failed after $maxAttempts attempts", lastError)
    }

    private fun retryAfterMillis(response: HttpResponse<*>): Long? =
        response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()?.let { it * 1000 }

    private fun backoff(attempt: Int) = sleepMillis(BACKOFF_BASE_MILLIS * attempt)

    private fun sleepMillis(millis: Long) {
        try {
            Thread.sleep(millis.coerceAtMost(MAX_BACKOFF_MILLIS))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private const val BACKOFF_BASE_MILLIS = 500L
        private const val MAX_BACKOFF_MILLIS = 10_000L
        private const val TOO_MANY_REQUESTS = 429
    }
}
