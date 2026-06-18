package net.kingmc.plugin.kingmcdonate.payment

import java.util.UUID

/** Accepts player-present rewards into the outbox. Implemented by [RewardDeliveryService]. */
interface RewardSink {
    fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload)
}
