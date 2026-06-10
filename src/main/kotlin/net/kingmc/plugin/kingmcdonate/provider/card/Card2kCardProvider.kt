package net.kingmc.plugin.kingmcdonate.provider.card

import com.google.gson.JsonParser
import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * card2k adapter (Nencer-style form API). Both charging and polling POST to
 * `/chargingws/v2`, differing only by `command`. The signature is
 * `md5(partner_key + code + serial)`; the request id (our reference code) is reused
 * to poll. A charge is asynchronous, so a submit only acknowledges intake (WAITING)
 * and success is confirmed by a later poll — status `1` means success on a poll but
 * never on a submit. Status `2` means a wrong denomination (the card is lost): it is
 * always FAILED, with the gateway-recognised `value` surfaced for the message. HTTP is
 * injected as a form-POST function so status mapping can be tested without a live gateway.
 */
class Card2kCardProvider(
    private val httpPostForm: (String, Map<String, String>) -> String,
    private val partnerId: String,
    private val partnerKey: String,
    private val enabledTypes: Set<CardType>,
    private val logger: PluginLogger,
) : CardProvider {

    override val name = NAME

    override fun supportedTypes(): Set<CardType> = enabledTypes

    override fun submit(request: CardRequest): CardOutcome = call(COMMAND_CHARGE, request, "submit", isCheck = false)

    override fun check(transactionId: String, request: CardRequest): CardOutcome =
        call(COMMAND_CHECK, request, "poll", isCheck = true)

    private fun call(command: String, request: CardRequest, label: String, isCheck: Boolean): CardOutcome {
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

        val body = httpPostForm(ENDPOINT, params)
        val json = JsonParser.parseString(body).asJsonObject
        val status = json.get("status")?.asInt
        val message = json.get("message")?.takeUnless { it.isJsonNull }?.asString ?: ""
        val recognized = json.get("value")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()
        val ref = request.referenceCode

        logger.debug { "card2k $label ref=$ref status=$status value=$recognized" }
        return when (status) {
            // Confirm success only from a poll; on a submit, status 1 just acknowledges intake.
            STATUS_SUCCESS ->
                if (isCheck) CardOutcome(PaymentStatus.SUCCESS, ref, recognized, message)
                else CardOutcome(PaymentStatus.WAITING, ref, recognized, message)
            STATUS_PENDING -> CardOutcome(PaymentStatus.WAITING, ref, recognized, message)
            STATUS_WRONG_PRICE -> CardOutcome(PaymentStatus.FAILED, ref, recognized, message.ifBlank { "Sai mệnh giá" })
            else -> CardOutcome(PaymentStatus.FAILED, ref, recognized, message.ifBlank { "Thẻ không hợp lệ" })
        }
    }

    companion object {
        const val NAME = "card2k"

        private const val ENDPOINT = "https://card2k.com/chargingws/v2"

        private const val COMMAND_CHARGE = "charging"
        private const val COMMAND_CHECK = "check"

        private const val STATUS_SUCCESS = 1
        private const val STATUS_WRONG_PRICE = 2
        private const val STATUS_PENDING = 99
    }
}
