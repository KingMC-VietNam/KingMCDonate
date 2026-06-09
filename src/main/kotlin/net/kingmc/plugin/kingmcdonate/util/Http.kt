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
 * errors and 5xx responses) are retried a bounded number of times with backoff.
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
        send(baseRequest(url).GET().build())

    fun postForm(url: String, params: Map<String, String>): String =
        send(
            baseRequest(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(params)))
                .build(),
        )

    private fun baseRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))

    /** Send with retry/backoff; returns the body on 2xx, retries IO/5xx, propagates 4xx immediately. */
    private fun send(request: HttpRequest): String {
        var lastError: Exception? = null
        for (attempt in 1..maxRetries) {
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                lastError = e
                logger.debug { "HTTP error (attempt $attempt/$maxRetries) for ${request.uri()}: ${e.message}" }
                if (attempt < maxRetries) backoff(attempt)
                continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("HTTP request interrupted", e)
            }

            val code = response.statusCode()
            if (code in 200..299) return response.body()
            // Client errors are not retryable — surface them immediately.
            if (code < 500) throw IOException("HTTP $code from ${request.uri()}")

            lastError = IOException("HTTP $code from ${request.uri()}")
            logger.debug { "HTTP $code (attempt $attempt/$maxRetries) for ${request.uri()}" }
            if (attempt < maxRetries) backoff(attempt)
        }
        throw IOException("HTTP request to ${request.uri()} failed after $maxRetries attempts", lastError)
    }

    private fun backoff(attempt: Int) {
        try {
            Thread.sleep(BACKOFF_BASE_MILLIS * attempt)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private const val BACKOFF_BASE_MILLIS = 500L
    }
}
