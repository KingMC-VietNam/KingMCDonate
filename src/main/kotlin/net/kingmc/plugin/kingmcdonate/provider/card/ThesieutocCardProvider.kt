package net.kingmc.plugin.kingmcdonate.provider.card

import com.google.gson.JsonParser
import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * thesieutoc adapter (legacy GET API). Charging submits to `/API/transaction` and
 * returns a `transaction_id` to poll at `/API/get_status_card.php`. The gateway
 * validates the denomination itself and reports a mismatch as poll status `10`, so
 * a successful poll carries no recognised amount. HTTP is injected as a GET function
 * so status mapping can be tested without a live gateway.
 */
class ThesieutocCardProvider(
    private val httpGet: (String) -> String,
    private val apiKey: String,
    private val apiSecret: String,
    private val enabledTypes: Set<CardType>,
    private val logger: PluginLogger,
) : CardProvider {

    override val name = NAME

    override fun supportedTypes(): Set<CardType> = enabledTypes

    override fun submit(request: CardRequest): CardOutcome {
        val telco = TELCO[request.type]
            ?: return failed("Card type ${request.type} not supported by $NAME")
        val denominationId = DENOMINATION_ID[request.declaredAmount]
            ?: return failed("Denomination ${request.declaredAmount} not supported by $NAME")

        val url = "$SUBMIT_URL?APIkey=${enc(apiKey)}&APIsecret=${enc(apiSecret)}" +
            "&mathe=${enc(request.pin)}&seri=${enc(request.serial)}&type=${enc(telco)}&menhgia=$denominationId"

        val body = httpGet(url)
        val json = JsonParser.parseString(body).asJsonObject
        val status = json.get("status")?.asString
        val message = json.get("msg")?.asString ?: ""
        val transactionId = json.get("transaction_id")?.takeUnless { it.isJsonNull }?.asString

        logger.debug { "thesieutoc submit ref=${request.referenceCode} status=$status tx=$transactionId" }
        return if (status == STATUS_OK && !transactionId.isNullOrBlank()) {
            CardOutcome(PaymentStatus.WAITING, transactionId, null, message)
        } else {
            CardOutcome(PaymentStatus.FAILED, null, null, message.ifBlank { "Cổng từ chối giao dịch" })
        }
    }

    override fun check(transactionId: String, request: CardRequest): CardOutcome {
        val url = "$STATUS_URL?APIkey=${enc(apiKey)}&APIsecret=${enc(apiSecret)}&transaction_id=${enc(transactionId)}"
        val body = httpGet(url)
        val json = JsonParser.parseString(body).asJsonObject
        val status = json.get("status")?.asString
        val message = json.get("msg")?.asString ?: ""

        logger.debug { "thesieutoc poll ref=${request.referenceCode} tx=$transactionId status=$status" }
        return when {
            status == STATUS_PENDING -> CardOutcome(PaymentStatus.WAITING, transactionId, null, message)
            status == STATUS_OK && message.contains(SUCCESS_MARKER, ignoreCase = true) ->
                CardOutcome(PaymentStatus.SUCCESS, transactionId, null, message)
            status == STATUS_WRONG_DENOMINATION ->
                CardOutcome(PaymentStatus.FAILED, transactionId, null, message.ifBlank { "Sai mệnh giá" })
            status == STATUS_FAILED ->
                CardOutcome(PaymentStatus.FAILED, transactionId, null, message.ifBlank { "Thẻ không hợp lệ" })
            else -> CardOutcome(PaymentStatus.WAITING, transactionId, null, message)
        }
    }

    private fun failed(reason: String) = CardOutcome(PaymentStatus.FAILED, null, null, reason)

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val NAME = "thesieutoc"

        private const val SUBMIT_URL = "http://vnpt.thesieutoc.net/API/transaction"
        private const val STATUS_URL = "https://thesieutoc.net/API/get_status_card.php"

        // The gateway returns "00" both to acknowledge a submit and to report a successful charge.
        private const val STATUS_OK = "00"
        private const val STATUS_PENDING = "-9"
        private const val STATUS_FAILED = "-10"
        private const val STATUS_WRONG_DENOMINATION = "10"
        private const val SUCCESS_MARKER = "nạp thành công"

        private val TELCO = mapOf(
            CardType.VIETTEL to "Viettel",
            CardType.MOBIFONE to "Mobifone",
            CardType.VINAPHONE to "Vinaphone",
            CardType.GARENA to "Garena",
            CardType.VCOIN to "Vcoin",
            CardType.ZING to "Zing",
            CardType.GATE to "Gate",
        )

        private val DENOMINATION_ID = mapOf(
            10_000L to 1, 20_000L to 2, 30_000L to 3, 50_000L to 4, 100_000L to 5,
            200_000L to 6, 300_000L to 7, 500_000L to 8, 1_000_000L to 9,
        )
    }
}
