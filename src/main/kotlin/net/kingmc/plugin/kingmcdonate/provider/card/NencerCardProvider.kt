package net.kingmc.plugin.kingmcdonate.provider.card

import com.google.gson.JsonParser
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.webhook.CardWebhookCapable
import net.kingmc.plugin.kingmcdonate.webhook.CardWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookHandler

/**
 * Adapter for Nencer-style card gateways (card2k, thesieure, …). They share one wire
 * protocol: charging and polling both POST to `<base-url>/chargingws/v2`, differing only
 * by `command`, signed with `md5(partner_key + code + serial)`; the request id (our
 * reference code) is reused to poll. A charge is asynchronous, so a submit only
 * acknowledges intake (WAITING) and success is confirmed by a later poll — status `1`
 * means success on a poll but never on a submit. Status `2` means a wrong denomination
 * (the card is lost): it is always FAILED, with the gateway-recognised `value` surfaced
 * for the message. Other known failures (`3`, `100`) are FAILED too; any unrecognised or
 * missing status is kept WAITING so the poll service retries rather than failing a
 * possibly-charged card. Concrete gateways differ only by [name] and [baseUrl]. HTTP is
 * injected as a form-POST function so status mapping can be tested without a live gateway.
 */
class NencerCardProvider(
    override val name: String,
    baseUrl: String,
    private val httpPostForm: (String, Map<String, String>, Boolean) -> String,
    private val partnerId: String,
    private val partnerKey: String,
    private val enabledTypes: Set<CardType>,
    private val logger: PluginLogger,
) : CardProvider, CardWebhookCapable {

    private val endpoint = "${baseUrl.trimEnd('/')}/chargingws/v2"

    override fun supportedTypes(): Set<CardType> = enabledTypes

    override fun webhookHandler(deps: CardWebhookDeps): WebhookHandler = NencerCallbackHandler(name, partnerKey, deps)

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

        // A charge POST must not be retried (a lost response could double-charge); the poll
        // reconciles a failed charge. A status check is idempotent, so it may retry.
        val body = httpPostForm(endpoint, params, isCheck)
        val ref = request.referenceCode

        // A non-JSON body (a maintenance/rate-limit HTML page) or a missing/non-numeric status is
        // ambiguous, never a confirmed result: keep the order WAITING so the poll service retries
        // rather than failing a possibly-charged card or throwing out of the poll sweep.
        val json = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        if (json == null) {
            logger.warn("$name $label ref=$ref: unparseable gateway response, keeping WAITING.")
            return CardOutcome(PaymentStatus.WAITING, ref, null, "")
        }
        val status = json.get("status")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
        val message = json.get("message")?.takeUnless { it.isJsonNull }?.asString ?: ""
        val recognized = json.get("value")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()

        logger.debug { "$name $label ref=$ref status=$status value=$recognized" }
        return when (status) {
            // Confirm success only from a poll; on a submit, status 1 just acknowledges intake.
            STATUS_SUCCESS ->
                if (isCheck) CardOutcome(PaymentStatus.SUCCESS, ref, recognized, message)
                else CardOutcome(PaymentStatus.WAITING, ref, recognized, message)
            STATUS_PENDING -> CardOutcome(PaymentStatus.WAITING, ref, recognized, message)
            // Known terminal failures: wrong denomination (card lost), a hard gateway error, or an
            // already-used card. Surface the gateway's own (Vietnamese) message; the payment layer
            // localizes when it is blank.
            STATUS_WRONG_PRICE, STATUS_ERROR, STATUS_USED -> CardOutcome(PaymentStatus.FAILED, ref, recognized, message)
            // Unrecognized or missing status: do not fail a possibly-charged card. Keep it WAITING so the
            // poll service re-checks (an undocumented "busy"/overload code resolves on a later pass; a truly
            // dead order is closed by the timeout sweep).
            else -> CardOutcome(PaymentStatus.WAITING, ref, recognized, message)
        }
    }

    companion object {
        const val CARD2K = "card2k"
        const val THESIEURE = "thesieure"

        /** card2k uses a fixed domain; thesieure's domain is partner-specific and read from config. */
        const val CARD2K_BASE_URL = "https://card2k.com"

        /** card2k's test gateway: resolves a card by the last 3 digits of its PIN (001/002/other). */
        const val CARD2K_SANDBOX_BASE_URL = "https://sandbox.card2k.com"

        private const val COMMAND_CHARGE = "charging"
        private const val COMMAND_CHECK = "check"

        private const val STATUS_SUCCESS = 1
        private const val STATUS_WRONG_PRICE = 2
        private const val STATUS_ERROR = 3
        private const val STATUS_PENDING = 99
        private const val STATUS_USED = 100
    }
}
