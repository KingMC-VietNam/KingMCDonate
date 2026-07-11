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

/**
 * Web2M gateway (Bank V3 history API, `api.web2m.com`). The QR is a public VietQR image
 * (`qr.sepay.vn/img`) built from the receiving account and the bank-type BIN, carrying the
 * reference as the transfer description. Confirmation is by polling the bank-type's V3
 * history endpoint (token in the URL, no auth header): each incoming (`type == "IN"`)
 * transfer is matched to an order when the transfer description contains the order's plain
 * reference and the amount matches exactly. Fixed-length references keep one order from
 * being a substring of another; the exact amount is the second guard.
 */
class Web2MBankProvider(
    private val httpGet: (String, Map<String, String>) -> String,
    private val accountNumber: String,
    private val bankType: BankType,
    private val password: String,
    private val token: String,
    private val logger: PluginLogger,
    private val accountHolder: String = "",
    private val webhookAuth: String = "none",
    private val webhookToken: String = "",
) : BankProvider, BankWebhookCapable {

    override val name = NAME

    override fun webhookHandler(deps: BankWebhookDeps): WebhookHandler =
        Web2MWebhookHandler(webhookAuth, webhookToken, deps)

    override fun createQr(amountVnd: Long, referenceCode: String): BankQr {
        val url = buildString {
            append("https://qr.sepay.vn/img?acc=").append(enc(accountNumber))
            append("&bank=").append(enc(bankType.bin))
            append("&amount=").append(amountVnd)
            append("&des=").append(enc(referenceCode))
            append("&template=qronly")
        }
        logger.debug { "Web2M QR url ref=$referenceCode amount=$amountVnd bank=${bankType.name}" }
        return BankQr(url, accountNumber, bankType.name, accountHolder)
    }

    override fun poll(orders: List<BankPayment>): List<BankConfirmation> {
        if (orders.isEmpty()) return emptyList()
        // V3 puts the credentials in the path: OpenAPI banks take only the token, the rest take
        // password/account/token. The token authenticates, so no header is sent.
        val url = if (bankType.oneParam) {
            "$BASE/${bankType.web2mPath}/$token"
        } else {
            "$BASE/${bankType.web2mPath}/$password/$accountNumber/$token"
        }
        val body = httpGet(url, emptyMap())
        val envelope = gson.fromJson(body, W2MEnvelope::class.java)
        val transactions = envelope?.transactions.orEmpty()
        logger.debug { "Web2M poll: ${transactions.size} tx(s), matching ${orders.size} order(s)" }
        return match(orders, transactions)
    }

    /** Pure matching: an incoming transfer confirms an order whose plain reference its description contains, at the exact amount. */
    fun match(orders: List<BankPayment>, transactions: List<W2MTransaction>): List<BankConfirmation> {
        val confirmations = ArrayList<BankConfirmation>()
        for (tx in transactions) {
            if (!tx.type.equals("IN", ignoreCase = true)) continue
            val txId = tx.transactionID ?: continue
            val amount = parseAmount(tx.amount) ?: continue
            val haystack = BankReference.searchText(null, tx.description)
            val matched = orders.firstOrNull { it.amount == amount && haystack.contains(it.referenceCode) } ?: continue
            confirmations.add(BankConfirmation(matched.referenceCode, txId, amount))
        }
        return confirmations
    }

    /** Strip non-digits (OUT rows already filtered, so no sign) and read as Long. */
    private fun parseAmount(raw: String?): Long? =
        raw?.replace(NON_DIGIT, "")?.toLongOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** Web2M V3 history envelope. */
    data class W2MEnvelope(
        @SerializedName("status") val status: Boolean = false,
        @SerializedName("message") val message: String? = null,
        @SerializedName("transactions") val transactions: List<W2MTransaction>? = null,
    )

    /** A Web2M V3 transaction. `amount`/`transactionID` may arrive as JSON string or number. */
    data class W2MTransaction(
        @SerializedName("transactionID") val transactionID: String? = null,
        @SerializedName("amount") val amount: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("transactionDate") val transactionDate: String? = null,
        @SerializedName("type") val type: String? = null,
    )

    companion object {
        const val NAME = "web2m"
        private const val BASE = "https://api.web2m.com"
        private val NON_DIGIT = Regex("[^0-9]")
        private val gson = Gson()
    }
}
