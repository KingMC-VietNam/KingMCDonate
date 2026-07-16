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
 * Receives Web2M's POST webhook: a batch `{status, data:[...]}` of transactions. Authenticity
 * is a static Bearer token compared constant-time (Web2M's payload carries no receiving account,
 * so there is nothing else to cross-check — the token is the whole guard, at the level of SePay's
 * apikey scheme). Each incoming (`type == "IN"`) item is matched to a pending order by the same
 * containment + exact-amount rule as polling and confirmed through the shared atomic guard using
 * the bank `transactionID`. Every authenticated request answers `200 {"status":true,"msg":"Ok"}`
 * — including transfers that match no order — so Web2M marks delivery done and does not retry.
 */
class Web2MWebhookHandler(
    private val auth: String,
    private val token: String,
    private val deps: BankWebhookDeps,
) : WebhookHandler {

    override val providerKey = Web2MBankProvider.NAME

    init {
        BankWebhookAuth.warnOnStartup(auth, Web2MBankProvider.NAME, deps.logger)
    }

    override fun handle(request: WebhookRequest): WebhookResponse {
        if (!verify(request)) {
            deps.logger.warn("Web2M webhook rejected: authentication failed (scheme=$auth).")
            return WebhookResponse.unauthorized()
        }

        val payload = runCatching { gson.fromJson(request.bodyString(), Payload::class.java) }.getOrNull()
        val items = payload?.data.orEmpty()
        if (items.isEmpty()) {
            deps.logger.warn("Web2M webhook: unparseable or empty data; acknowledged, no change.")
            return ACK
        }

        for (item in items) {
            if (!item.type.equals("IN", ignoreCase = true)) continue
            val txId = item.transactionID ?: continue
            val amount = parseAmount(item.amount) ?: continue
            val haystack = BankReference.searchText(null, item.description)
            val order = deps.findPendingByContainedReference(haystack, amount)
            if (order == null) {
                KingMCDonateContext.activityLogOrNull?.log(
                    "WEBHOOK", "web2m tx=$txId amount=$amount matched no pending order",
                )
                deps.logger.warn("Web2M webhook: authentic transfer tx=$txId matched no pending order; acknowledged.")
                continue
            }
            deps.logger.debug { "Web2M webhook tx=$txId matched ref=${order.referenceCode}; confirming." }
            KingMCDonateContext.activityLogOrNull?.log(
                "WEBHOOK", "web2m tx=$txId amount=$amount matched ref=${order.referenceCode}",
            )
            deps.confirm(BankConfirmation(order.referenceCode, txId, amount))
        }
        return ACK
    }

    private fun verify(request: WebhookRequest): Boolean = when (auth.lowercase()) {
        AUTH_NONE -> true
        AUTH_BEARER ->
            token.isNotBlank() && Hashing.constantTimeEquals("Bearer $token", request.header("Authorization").orEmpty())
        else -> {
            deps.logger.warn("Web2M webhook: unknown webhook-auth '$auth'; rejecting.")
            false
        }
    }

    /** Strip non-digits (OUT rows already filtered, so no sign) and read as Long. */
    private fun parseAmount(raw: String?): Long? = raw?.replace(NON_DIGIT, "")?.toLongOrNull()

    /** Web2M webhook batch. `data` items may carry `amount`/`transactionID` as JSON string or number. */
    private data class Payload(
        @SerializedName("status") val status: Boolean = false,
        @SerializedName("data") val data: List<Item>? = null,
    )

    private data class Item(
        @SerializedName("id") val id: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("transactionID") val transactionID: String? = null,
        @SerializedName("amount") val amount: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("date") val date: String? = null,
        @SerializedName("bank") val bank: String? = null,
    )

    companion object {
        private const val AUTH_NONE = "none"
        private const val AUTH_BEARER = "bearer"
        private val NON_DIGIT = Regex("[^0-9]")
        private val ACK = WebhookResponse.json("""{"status":true,"msg":"Ok"}""")
        private val gson = Gson()
    }
}
