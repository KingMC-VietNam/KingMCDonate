package net.kingmc.plugin.kingmcdonate.provider.bank

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SePay gateway. The QR is a SePay-hosted VietQR image (`qr.sepay.vn/img`) pointing
 * at the receiving account with the reference carried as the transfer description.
 * Confirmation is by polling SePay API v2 (`GET {base}/transactions`, Bearer token):
 * each incoming transfer is matched to an order by SePay's extracted `code` when
 * present, otherwise by an exact `[A-Z0-9]` token in the content, plus an exact
 * amount. Matching never uses a loose substring, so a reference cannot match inside
 * a longer token.
 */
class SePayBankProvider(
    private val httpGet: (String, Map<String, String>) -> String,
    private val accountNumber: String,
    private val bank: String,
    private val apiToken: String,
    private val sandbox: Boolean,
    private val logger: PluginLogger,
) : BankProvider {

    override val name = NAME

    override fun createQr(amountVnd: Long, referenceCode: String): BankQr {
        val url = buildString {
            append("https://qr.sepay.vn/img?acc=").append(enc(accountNumber))
            append("&bank=").append(enc(bank))
            append("&amount=").append(amountVnd)
            append("&des=").append(enc(referenceCode))
            append("&template=qronly")
        }
        logger.debug { "SePay QR url ref=$referenceCode amount=$amountVnd" }
        return BankQr(url)
    }

    override fun poll(orders: List<BankPayment>): List<BankConfirmation> {
        if (orders.isEmpty()) return emptyList()
        val base = if (sandbox) SANDBOX_BASE else PRODUCTION_BASE
        val url = "$base/transactions?account_number=${enc(accountNumber)}&limit=$PAGE_SIZE"
        val body = httpGet(url, mapOf("Authorization" to "Bearer $apiToken"))
        val envelope = gson.fromJson(body, SePayEnvelope::class.java)
        val transactions = envelope?.data.orEmpty()
        logger.debug { "SePay poll: ${transactions.size} tx(s) returned, matching ${orders.size} order(s)" }
        return match(orders, transactions)
    }

    /** Pure matching: each incoming transfer maps to at most one order by code/token + exact amount. */
    fun match(orders: List<BankPayment>, transactions: List<SePayTransaction>): List<BankConfirmation> {
        val byReference = orders.associateBy { it.referenceCode }
        val confirmations = ArrayList<BankConfirmation>()
        for (tx in transactions) {
            if (!tx.transferType.equals("in", ignoreCase = true)) continue
            val txId = tx.id ?: continue
            val candidates = referenceCandidates(tx)
            val matched = candidates.firstNotNullOfOrNull { ref ->
                byReference[ref]?.takeIf { it.amount == tx.amountIn }
            } ?: continue
            confirmations.add(BankConfirmation(matched.referenceCode, txId, tx.amountIn))
        }
        return confirmations
    }

    /** Reference candidates from a transfer: the extracted code when present, else exact content tokens. */
    private fun referenceCandidates(tx: SePayTransaction): Set<String> {
        val code = tx.code?.trim()?.uppercase()
        if (!code.isNullOrEmpty()) return setOf(code)
        val content = tx.content ?: return emptySet()
        return content.uppercase().split(NON_ALNUM).filter { it.isNotEmpty() }.toSet()
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
        private const val PAGE_SIZE = 50
        private val NON_ALNUM = Regex("[^A-Z0-9]+")
        private val gson = Gson()
    }
}
