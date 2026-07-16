package net.kingmc.plugin.kingmcdonate.payment.reward

/**
 * A player-present reward serialized into the `pending_reward` outbox. Both command
 * lines and the [message] field are stored already substituted (variables resolved at
 * enqueue time) so the delivering node needs no context beyond the row.
 *
 * [message] is an optional raw, already-variable-substituted line; color codes are
 * applied at delivery time via [net.kingmc.plugin.kingmcdonate.util.Text.colorize].
 */
data class RewardPayload(
    val messageKey: String? = null,
    val messageVars: Map<String, String> = emptyMap(),
    val commands: List<String> = emptyList(),
    val message: String? = null,
)
