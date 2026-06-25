package net.kingmc.plugin.kingmcdonate.webhook

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A uniform view of an inbound webhook request. Headers are matched
 * case-insensitively; [rawBody] is the unmodified request body exactly as received,
 * so handlers that verify a signature over the raw bytes (e.g. HMAC) compute it
 * correctly — it is never parsed and re-serialized.
 */
class WebhookRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    headers: Map<String, String>,
    val rawBody: ByteArray,
) {

    private val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    /** Header value by case-insensitive name, or null when absent. */
    fun header(name: String): String? = headers[name.lowercase()]

    /** The raw body decoded as UTF-8 text. */
    fun bodyString(): String = String(rawBody, StandardCharsets.UTF_8)

    companion object {
        /** Parse a raw URL query string (`a=1&b=2`) into a decoded map; later keys win. */
        fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                out[decode(key)] = decode(value)
            }
            return out
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
