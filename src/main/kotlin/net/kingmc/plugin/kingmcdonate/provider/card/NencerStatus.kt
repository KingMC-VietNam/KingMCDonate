package net.kingmc.plugin.kingmcdonate.provider.card

/**
 * Nencer-style gateway status codes (card2k, thesieure, …), the single source of truth shared
 * by the poll adapter [NencerCardProvider] and the callback handler [NencerCallbackHandler] so
 * the two never drift. Only the code *values* are shared: how each maps to a
 * [net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus] depends on context (a submit only
 * acknowledges intake, a poll/callback confirms), so the mapping stays with each caller.
 */
internal object NencerStatus {
    const val SUCCESS = 1
    const val WRONG_PRICE = 2
    const val ERROR = 3
    const val MAINTENANCE = 4
    const val PENDING = 99
    const val USED = 100
}
