package net.kingmc.plugin.kingmcdonate.payment.model

import java.util.UUID

/** A persisted bank top-up record (a row of `bank_payments`). */
data class BankPayment(
    val id: Long,
    val playerUuid: UUID,
    val amount: Long,
    val referenceCode: String,
    val status: PaymentStatus,
    val provider: String,
    val ownerServer: String,
    val externalRef: String?,
    val point: Long,
    val rewardApplied: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
