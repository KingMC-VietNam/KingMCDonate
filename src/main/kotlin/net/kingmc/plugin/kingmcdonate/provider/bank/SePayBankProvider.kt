package net.kingmc.plugin.kingmcdonate.provider.bank

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookCapable
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookHandler
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SePay gateway. The QR is a SePay-hosted VietQR image (`qr.sepay.vn/img`) pointing
 * at the receiving account with the reference carried as the transfer description.
 * Confirmation is by polling SePay API v2 (`GET {base}/transactions`, Bearer token):
 * each incoming transfer is matched to an order when the transfer text (SePay's
 * extracted `code` plus the content) contains the order's plain reference and the
 * amount matches exactly. Fixed-length references keep one order from being a
 * substring of another; the exact amount is the second guard.
 */
class SePayBankProvider(
    private val httpGet: (String, Map<String, String>) -> String,
    private val accountNumber: String,
    private val bank: String,
    private val apiToken: String,
    private val sandbox: Boolean,
    private val logger: PluginLogger,
    // Blank, not "none": an unspecified scheme must reject, never accept every webhook.
    private val webhookAuth: String = "",
    private val webhookSecret: String = "",
    private val webhookApiKey: String = "",
    private val accountHolder: String = "",
) : BankProvider, BankWebhookCapable {

    override val name = NAME

    override fun webhookHandler(deps: BankWebhookDeps): WebhookHandler =
        SePayWebhookHandler(webhookAuth, webhookSecret, webhookApiKey, accountNumber, deps)

    override fun createQr(amountVnd: Long, referenceCode: String): BankQr {
        val url = buildString {
            append("https://qr.sepay.vn/img?acc=").append(enc(accountNumber))
            append("&bank=").append(enc(bank))
            append("&amount=").append(amountVnd)
            append("&des=").append(enc(referenceCode))
            append("&template=qronly")
        }
        logger.debug { "SePay QR url ref=$referenceCode amount=$amountVnd" }
        return BankQr(url, accountNumber, bank, accountHolder)
    }

    override fun poll(orders: List<BankPayment>): BankPollResult {
        if (orders.isEmpty()) return BankPollResult.EMPTY
        val base = if (sandbox) SANDBOX_BASE else PRODUCTION_BASE
        // Bound the page to transactions since the oldest live order (minus a generous buffer that
        // absorbs any clock/timezone skew) so a busy account can't push a pending transfer off the
        // newest page; `per_page` is the documented SePay parameter (an unfiltered `limit` is ignored).
        val dateFrom = formatDate(orders.minOf { it.createdAt } - LOOKBACK_BUFFER_MILLIS)
        val url = "$base/transactions?account_number=${enc(accountNumber)}" +
            "&transaction_date_from=${enc(dateFrom)}&per_page=$PER_PAGE"
        val body = httpGet(url, mapOf("Authorization" to "Bearer $apiToken"))
        val envelope = gson.fromJson(body, SePayEnvelope::class.java)
        val transactions = envelope?.data.orEmpty()
        logger.debug { "SePay poll: ${transactions.size} tx(s) since $dateFrom, matching ${orders.size} order(s)" }
        return match(orders, transactions)
    }

    /** SePay expects `yyyy-MM-dd HH:mm:ss` in the account's local time; format in the JVM default zone. */
    private fun formatDate(epochMillis: Long): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    /**
     * Pure matching: an incoming transfer confirms an order whose plain reference the transfer text
     * contains, at the exact amount. The exact-amount predicate is load-bearing — a transfer's text
     * can name several orders, and the amount is what picks the right one.
     *
     * A transfer matching no order goes to [BankPollResult.unmatched] so the core can surface it.
     * It must not be reported from here: that would also flag transfers that go on to be credited.
     */
    fun match(orders: List<BankPayment>, transactions: List<SePayTransaction>): BankPollResult {
        val confirmations = ArrayList<BankConfirmation>()
        val unmatched = ArrayList<UnmatchedTransfer>()
        for (tx in transactions) {
            if (!tx.transferType.equals("in", ignoreCase = true)) continue
            val txId = tx.id ?: continue
            val haystack = BankReference.searchText(tx.code, tx.content)
            val matched = orders.firstOrNull { it.amount == tx.amountIn && haystack.contains(it.referenceCode) }
            if (matched == null) {
                unmatched.add(UnmatchedTransfer(txId, haystack, tx.amountIn))
                continue
            }
            confirmations.add(BankConfirmation(matched.referenceCode, txId, tx.amountIn))
        }
        return BankPollResult(confirmations, unmatched)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** SePay API v2 transaction-list envelope. */
    data class SePayEnvelope(
        @SerializedName("status") val status: String? = null,
        @SerializedName("data") val data: List<SePayTransaction>? = null,
    )

    /** A SePay API v2 transaction (list model): integer amounts, snake_case fields. */
    data class SePayTransaction(
        @SerializedName("id") val id: String? = null,
        @SerializedName("transfer_type") val transferType: String? = null,
        @SerializedName("amount_in") val amountIn: Long = 0,
        @SerializedName("amount_out") val amountOut: Long = 0,
        @SerializedName("transaction_content") val content: String? = null,
        @SerializedName("reference_number") val referenceNumber: String? = null,
        @SerializedName("code") val code: String? = null,
    )

    companion object {
        const val NAME = "sepay"
        private const val PRODUCTION_BASE = "https://userapi.sepay.vn/v2"
        private const val SANDBOX_BASE = "https://userapi-sandbox.sepay.vn/v2"
        private const val PER_PAGE = 100 // SePay caps per_page at 100
        private const val LOOKBACK_BUFFER_MILLIS = 2L * 24 * 60 * 60 * 1000 // 2 days, absorbs clock/timezone skew
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val gson = Gson()
    }
}
