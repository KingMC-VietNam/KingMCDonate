package net.kingmc.plugin.kingmcdonate.payment

import java.util.UUID

/** A persisted card top-up record (a row of `card_payments`). */
data class CardPayment(
    val id: Long,
    val playerUuid: UUID,
    val playerName: String?,
    val cardType: String,
    val amount: Long,
    val serial: String,
    val pin: String,
    val status: PaymentStatus,
    val referenceCode: String,
    val cardProvider: String,
    val transactionId: String?,
    val ownerServer: String,
    val point: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
