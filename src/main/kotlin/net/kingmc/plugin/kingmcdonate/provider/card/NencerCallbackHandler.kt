package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.webhook.CardWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookHandler
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRequest
import net.kingmc.plugin.kingmcdonate.webhook.WebhookResponse

/**
 * Receives a Nencer-style gateway's GET callback (card2k, thesieure, …). Authenticity is
 * `md5(partner_key + code + serial)` compared constant-time to `callback_sign`. The
 * `request_id` is our reference code; the gateway `status` maps to a card outcome that is
 * resolved through the shared idempotent path, so a callback and a poll (in `both` mode)
 * credit at most once. The gateway expects an empty 200 acknowledgement. [providerKey]
 * is the active gateway's name, used as the webhook route segment.
 */
class NencerCallbackHandler(
    override val providerKey: String,
    private val partnerKey: String,
    private val deps: CardWebhookDeps,
) : WebhookHandler {

    override fun handle(request: WebhookRequest): WebhookResponse {
        val q = request.query
        val code = q["code"].orEmpty()
        val serial = q["serial"].orEmpty()
        val sign = q["callback_sign"].orEmpty()
        val expected = Hashing.md5Hex(partnerKey + code + serial)
        if (!Hashing.constantTimeEquals(expected, sign)) {
            deps.logger.warn("$providerKey callback rejected: bad signature (request_id=${q["request_id"]}).")
            return WebhookResponse.unauthorized()
        }

        val reference = q["request_id"]
        if (reference.isNullOrBlank()) {
            deps.logger.warn("$providerKey callback missing request_id; ignored.")
            return WebhookResponse.ok()
        }
        val order = deps.findByReference(reference)
        if (order == null) {
            deps.logger.warn("$providerKey callback for unknown reference=$reference; acknowledged, no change.")
            return WebhookResponse.ok()
        }

        val status = q["status"]?.trim()?.toIntOrNull()
        val recognized = q["value"]?.toLongOrNull()
        val message = q["message"].orEmpty()
        val transactionId = q["trans_id"]
        val outcome = CardOutcome(mapStatus(status), transactionId, recognized, message)

        deps.logger.debug { "$providerKey callback ref=$reference status=$status -> ${outcome.status}" }
        deps.applyOutcome(reference, order.playerUuid, order.playerName, order.amount, outcome)
        return WebhookResponse.ok()
    }

    /** Nencer callback status codes; `2` (wrong denomination) is a strict-match FAILED. */
    private fun mapStatus(status: Int?): PaymentStatus = when (status) {
        STATUS_SUCCESS -> PaymentStatus.SUCCESS
        STATUS_MAINTENANCE, STATUS_PENDING -> PaymentStatus.WAITING
        else -> PaymentStatus.FAILED
    }

    companion object {
        private const val STATUS_SUCCESS = 1
        private const val STATUS_MAINTENANCE = 4
        private const val STATUS_PENDING = 99
    }
}
