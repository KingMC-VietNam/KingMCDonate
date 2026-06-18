package net.kingmc.plugin.kingmcdonate.payment

import java.util.UUID

/** An outbox row (`pending_reward`): a player-present reward awaiting delivery. */
data class PendingReward(
    val id: Long,
    val playerUuid: UUID,
    val referenceCode: String,
    val payload: String,
)
