package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
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

        // The signature proves the caller knows *a* valid (code, serial), not that it belongs to this
        // order. A physical card belongs to exactly one order — the one that submitted and stored it — so
        // a signature re-bound to a different reference names a card this order never held. Reject it, or a
        // single captured/self-bought callback replays onto any pending order. Compare trimmed and case-
        // insensitively: we store what the player typed, and a gateway that echoes it re-cased or re-padded
        // must not cost a charged card its credit (webhook-only mode runs no final check to recover it).
        if (!cardMatches(serial, order.serial) || !cardMatches(code, order.pin)) {
            deps.logger.error("$providerKey callback rejected: card does not match order (reference=$reference).")
            return WebhookResponse.unauthorized()
        }

        val status = q["status"]?.trim()?.toIntOrNull()
        val recognized = q["value"]?.toLongOrNull()
        val message = q["message"].orEmpty()
        val transactionId = q["trans_id"]
        val outcome = CardOutcome(mapStatus(status), transactionId, recognized, message, wrongDenomination = status == NencerStatus.WRONG_PRICE)

        deps.logger.debug { "$providerKey callback ref=$reference status=$status -> ${outcome.status}" }
        KingMCDonateContext.activityLogOrNull?.log(
            "WEBHOOK", "$providerKey callback ref=$reference status=$status -> ${outcome.status}",
        )
        deps.applyOutcome(reference, order.playerUuid, order.playerName, order.amount, outcome)
        return WebhookResponse.ok()
    }

    /**
     * Nencer callback status codes, mapped exactly like the poll adapter so a callback can never
     * terminally FAIL a possibly-charged card on an ambiguous status: `1` success; `2` (wrong
     * denomination, card lost), `3` and `100` are FAILED; `4` (maintenance) and `99` (pending) stay
     * WAITING; any unknown or missing status stays WAITING so the poll service reconciles instead of
     * failing a card that may have been charged.
     */
    private fun mapStatus(status: Int?): PaymentStatus = when (status) {
        NencerStatus.SUCCESS -> PaymentStatus.SUCCESS
        NencerStatus.WRONG_PRICE, NencerStatus.ERROR, NencerStatus.USED -> PaymentStatus.FAILED
        NencerStatus.MAINTENANCE, NencerStatus.PENDING -> PaymentStatus.WAITING
        else -> PaymentStatus.WAITING
    }

    /** Constant-time compare of a callback-supplied card value against the stored one, trimmed and case-folded. */
    private fun cardMatches(supplied: String, stored: String): Boolean =
        Hashing.constantTimeEquals(supplied.trim().lowercase(), stored.trim().lowercase())
}
