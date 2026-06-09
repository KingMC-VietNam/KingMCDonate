package net.kingmc.plugin.kingmcdonate.provider.card

import com.google.gson.JsonParser
import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * card2k adapter (Nencer-style form API). Both charging and polling POST to
 * `/chargingws/v2`, differing only by `command`. The signature is
 * `md5(partner_key + code + serial)`; the request id (our reference code) is reused
 * to poll. A recognised card `value` is surfaced so the payment layer can reject a
 * denomination mismatch. HTTP is injected as a form-POST function so status mapping
 * can be tested without a live gateway.
 */
class Card2kCardProvider(
    private val httpPostForm: (String, Map<String, String>) -> String,
    private val partnerId: String,
    private val partnerKey: String,
    private val sandbox: Boolean,
    private val enabledTypes: Set<CardType>,
    private val logger: PluginLogger,
) : CardProvider {

    override val name = NAME

    override fun supportedTypes(): Set<CardType> = enabledTypes

    override fun submit(request: CardRequest): CardOutcome = call(COMMAND_CHARGE, request, "submit")

    override fun check(transactionId: String, request: CardRequest): CardOutcome =
        call(COMMAND_CHECK, request, "poll")

    private fun call(command: String, request: CardRequest, label: String): CardOutcome {
        val telco = request.type.name
        val params = mapOf(
            "partner_id" to partnerId,
            "command" to command,
            "telco" to telco,
            "code" to request.pin,
            "serial" to request.serial,
            "amount" to request.declaredAmount.toString(),
            "request_id" to request.referenceCode,
            "sign" to Hashing.md5Hex(partnerKey + request.pin + request.serial),
        )

        val body = httpPostForm(endpoint(), params)
        val json = JsonParser.parseString(body).asJsonObject
        val status = json.get("status")?.asInt
        val message = json.get("message")?.takeUnless { it.isJsonNull }?.asString ?: ""
        val recognized = json.get("value")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()

        logger.debug { "card2k $label ref=${request.referenceCode} status=$status value=$recognized" }
        return when (status) {
            in SUCCESS_STATUSES -> CardOutcome(PaymentStatus.SUCCESS, request.referenceCode, recognized, message)
            STATUS_PENDING -> CardOutcome(PaymentStatus.WAITING, request.referenceCode, recognized, message)
            else -> CardOutcome(
                PaymentStatus.FAILED,
                request.referenceCode,
                recognized,
                message.ifBlank { "Thẻ không hợp lệ" },
            )
        }
    }

    private fun endpoint(): String = (if (sandbox) SANDBOX_HOST else PRODUCTION_HOST) + PATH

    companion object {
        const val NAME = "card2k"

        private const val PRODUCTION_HOST = "https://card2k.net"
        private const val SANDBOX_HOST = "https://sandbox.card2k.net"
        private const val PATH = "/chargingws/v2"

        private const val COMMAND_CHARGE = "charging"
        private const val COMMAND_CHECK = "check"

        private val SUCCESS_STATUSES = setOf(1, 2)
        private const val STATUS_PENDING = 99
    }
}
