package net.kingmc.plugin.kingmcdonate.payment.reward

/**
 * A player-present reward serialized into the `pending_reward` outbox. Command lines
 * are stored already substituted (`{player}/{amount}/{point}/{ref}` resolved at
 * enqueue time) so a stale-claim requeue replays identically. Message variables are
 * deterministic and applied when the message is sent.
 */
data class RewardPayload(
    val messageKey: String? = null,
    val messageVars: Map<String, String> = emptyMap(),
    val commands: List<String> = emptyList(),
)
