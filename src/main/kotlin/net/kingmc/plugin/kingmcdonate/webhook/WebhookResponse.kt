package net.kingmc.plugin.kingmcdonate.webhook

/**
 * A handler's reply. Each gateway dictates its own success shape, so handlers build
 * the exact [status], [contentType] and [body] their gateway expects (SePay needs
 * `200 {"success":true}`; card2k needs an empty 200).
 */
class WebhookResponse(
    val status: Int,
    val contentType: String,
    val body: String,
) {
    companion object {
        fun ok(body: String = "", contentType: String = "text/plain"): WebhookResponse =
            WebhookResponse(200, contentType, body)

        fun json(body: String): WebhookResponse =
            WebhookResponse(200, "application/json", body)

        fun unauthorized(): WebhookResponse =
            WebhookResponse(401, "text/plain", "unauthorized")

        fun notFound(): WebhookResponse =
            WebhookResponse(404, "text/plain", "not found")
    }
}
