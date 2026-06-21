package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import java.util.UUID

/** Telcos/brands a card can belong to; each gateway maps these to its own wire string. */
enum class CardType {
    VIETTEL,
    MOBIFONE,
    VINAPHONE,
    GARENA,
    VCOIN,
    ZING,
    GATE,
    ;

    companion object {
        fun parse(value: String): CardType? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** A single card-charge request. [referenceCode] doubles as the gateway request id. */
data class CardRequest(
    val playerUuid: UUID,
    val type: CardType,
    val declaredAmount: Long,
    val serial: String,
    val pin: String,
    val referenceCode: String,
)

/**
 * Uniform result of a submit or poll. [status] is always WAITING, SUCCESS or
 * FAILED; [recognizedAmount] is the value the gateway actually recognised (null
 * when the gateway returns none) so the payment layer can enforce amount matching.
 */
data class CardOutcome(
    val status: PaymentStatus,
    val transactionId: String?,
    val recognizedAmount: Long?,
    val message: String,
)

/**
 * A card-charging gateway. Charging is two steps: [submit] sends the card and may
 * return WAITING with a transaction handle; [check] re-polls that handle until the
 * order reaches a terminal status. Implementations only map their wire protocol —
 * the payment lifecycle, persistence and rewarding live outside.
 */
interface CardProvider {

    /** Stable identifier matching the `card.provider` config value. */
    val name: String

    /** Card types this gateway (as configured) will accept. */
    fun supportedTypes(): Set<CardType>

    /** Send a charge; WAITING carries the handle to poll, SUCCESS/FAILED are terminal. */
    fun submit(request: CardRequest): CardOutcome

    /** Re-poll a WAITING order by its gateway transaction handle. */
    fun check(transactionId: String, request: CardRequest): CardOutcome
}
