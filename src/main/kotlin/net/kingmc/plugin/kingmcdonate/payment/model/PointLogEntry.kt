package net.kingmc.plugin.kingmcdonate.payment.model

import java.util.UUID

/**
 * One row of the point-change ledger: a single credit applied to a player, kept
 * append-only and separate from the card/bank transaction tables so admins can
 * audit balance movement (dispute tracing, admin over-reach) without gateway noise.
 * [actor] is set only for admin-issued manual credit; card/bank rows leave it null.
 */
data class PointLogEntry(
    val playerUuid: UUID,
    val playerName: String?,
    val amount: Long,
    val method: String,
    val provider: String?,
    val referenceCode: String?,
    val actor: String?,
    val server: String,
    val content: String?,
    val createdAt: Long,
)
