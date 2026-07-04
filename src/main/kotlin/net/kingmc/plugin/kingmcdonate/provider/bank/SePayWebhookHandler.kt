package net.kingmc.plugin.kingmcdonate.provider.bank

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookHandler
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRequest
import net.kingmc.plugin.kingmcdonate.webhook.WebhookResponse

/**
 * Receives SePay's POST webhook. Authenticity is verified over the raw request body
 * per the configured scheme (HMAC-SHA256 with replay protection, API key, or none).
 * An incoming transfer is matched to a pending order by the same reference rule as
 * polling and confirmed through the shared atomic guard using SePay's `id`. Every
 * authenticated request answers `200 {"success":true}` — including authentic transfers
 * that match no order — so SePay marks delivery done and does not retry.
 */
class SePayWebhookHandler(
    private val auth: String,
    private val secret: String,
    private val apiKey: String,
    private val accountNumber: String,
    private val deps: BankWebhookDeps,
) : WebhookHandler {

    override val providerKey = SePayBankProvider.NAME

    override fun handle(request: WebhookRequest): WebhookResponse {
        if (!verify(request)) {
            deps.logger.warn("SePay webhook rejected: authentication failed (scheme=$auth).")
            return WebhookResponse.unauthorized()
        }

        val payload = runCatching { gson.fromJson(request.bodyString(), Payload::class.java) }.getOrNull()
        if (payload?.id == null) {
            deps.logger.warn("SePay webhook: unparseable or id-less payload; acknowledged, no change.")
            return ACK
        }
        if (!payload.transferType.equals("in", ignoreCase = true)) {
            deps.logger.debug { "SePay webhook tx=${payload.id} is not incoming; acknowledged." }
            return ACK
        }
        // Validate the transfer landed on our configured receiving account. With a non-HMAC scheme the
        // payload isn't integrity-protected, so a transfer to a different account must never confirm an
        // order here. Skip only when the payload omits the field (can't validate) or no account is set.
        val payloadAccount = payload.accountNumber?.trim()
        if (!payloadAccount.isNullOrEmpty() && accountNumber.isNotBlank() && payloadAccount != accountNumber) {
            deps.logger.warn(
                "SePay webhook tx=${payload.id} for account $payloadAccount != configured $accountNumber; " +
                    "acknowledged, no change.",
            )
            return ACK
        }

        val txId = payload.id.toString()
        val candidates = SePayReference.candidates(payload.code, payload.content)
        val orders = candidates.mapNotNull { deps.findByReference(it) }
        // Prefer the order whose amount matches the transfer (mirrors the polling match rule) so a
        // stray second token in the content can't lock onto the wrong order; fall back to the first.
        val order = orders.firstOrNull { it.amount == payload.transferAmount } ?: orders.firstOrNull()
        if (order != null) {
            deps.logger.debug { "SePay webhook tx=$txId matched ref=${order.referenceCode}; confirming." }
            KingMCDonateContext.activityLogOrNull?.log(
                "WEBHOOK", "sepay tx=$txId amount=${payload.transferAmount} matched ref=${order.referenceCode}",
            )
            deps.confirm(BankConfirmation(order.referenceCode, txId, payload.transferAmount))
            return ACK
        }
        KingMCDonateContext.activityLogOrNull?.log(
            "WEBHOOK", "sepay tx=$txId amount=${payload.transferAmount} matched no pending order",
        )
        deps.logger.warn("SePay webhook: authentic transfer tx=$txId matched no pending order; acknowledged.")
        return ACK
    }

    private fun verify(request: WebhookRequest): Boolean = when (auth.lowercase()) {
        AUTH_NONE -> true
        AUTH_APIKEY -> {
            val header = request.header("Authorization").orEmpty()
            apiKey.isNotBlank() && Hashing.constantTimeEquals("Apikey $apiKey", header)
        }
        AUTH_HMAC -> verifyHmac(request)
        else -> {
            deps.logger.warn("SePay webhook: unknown webhook-auth '$auth'; rejecting.")
            false
        }
    }

    private fun verifyHmac(request: WebhookRequest): Boolean {
        if (secret.isBlank()) return false
        val signature = request.header("X-SePay-Signature").orEmpty()
        val timestamp = request.header("X-SePay-Timestamp")?.trim()?.toLongOrNull() ?: return false
        val now = System.currentTimeMillis() / 1000L
        if (Math.abs(now - timestamp) > MAX_SKEW_SECONDS) {
            deps.logger.warn("SePay webhook: timestamp skew too large (ts=$timestamp); rejecting.")
            return false
        }
        val expected = "sha256=" + Hashing.hmacSha256Hex(secret, "$timestamp.${request.bodyString()}")
        return Hashing.constantTimeEquals(expected, signature)
    }

    /** SePay webhook payload (camelCase). `code` is the auto-extracted reference; `id` is the dedup key. */
    private data class Payload(
        @SerializedName("id") val id: Long? = null,
        @SerializedName("transferType") val transferType: String? = null,
        @SerializedName("transferAmount") val transferAmount: Long = 0,
        @SerializedName("accountNumber") val accountNumber: String? = null,
        @SerializedName("content") val content: String? = null,
        @SerializedName("code") val code: String? = null,
    )

    companion object {
        private const val AUTH_NONE = "none"
        private const val AUTH_APIKEY = "apikey"
        private const val AUTH_HMAC = "hmac"
        private const val MAX_SKEW_SECONDS = 300L
        private val ACK = WebhookResponse.json("""{"success":true}""")
        private val gson = Gson()
    }
}
