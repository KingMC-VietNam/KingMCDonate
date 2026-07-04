package net.kingmc.plugin.kingmcdonate.bedrock

import net.kingmc.plugin.kingmcdonate.provider.card.CardType

/**
 * Pure decode of a submitted card form: map the type/denomination dropdown indices back to a
 * [CardType] and amount/point, and validate the serial and PIN. Kept free of any Cumulus type
 * so the mapping — the part that could charge the wrong card — is unit-testable.
 */
sealed interface CardFormSelection {

    data class Ok(
        val type: CardType,
        val amount: Long,
        val point: Long,
        val serial: String,
        val pin: String,
    ) : CardFormSelection

    data class Invalid(val reason: Reason) : CardFormSelection

    enum class Reason { TYPE_INDEX, PRICE_INDEX, EMPTY_SERIAL, EMPTY_PIN }

    companion object {
        fun resolve(
            enabledTypes: List<CardType>,
            denominations: List<Pair<Long, Long>>,
            typeIndex: Int,
            priceIndex: Int,
            serial: String?,
            pin: String?,
        ): CardFormSelection {
            val type = enabledTypes.getOrNull(typeIndex) ?: return Invalid(Reason.TYPE_INDEX)
            val denom = denominations.getOrNull(priceIndex) ?: return Invalid(Reason.PRICE_INDEX)
            val trimmedSerial = serial?.trim().orEmpty()
            if (trimmedSerial.isEmpty()) return Invalid(Reason.EMPTY_SERIAL)
            val trimmedPin = pin?.trim().orEmpty()
            if (trimmedPin.isEmpty()) return Invalid(Reason.EMPTY_PIN)
            return Ok(type, denom.first, denom.second, trimmedSerial, trimmedPin)
        }
    }
}
