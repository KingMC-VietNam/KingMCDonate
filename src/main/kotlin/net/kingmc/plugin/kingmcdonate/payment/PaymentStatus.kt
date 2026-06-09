package net.kingmc.plugin.kingmcdonate.payment

/**
 * Lifecycle status shared by card and bank payments. `PENDING` is the freshly
 * created record, `WAITING` an order the gateway has accepted but not finalised
 * (re-polled until terminal), and `SUCCESS`/`FAILED` are terminal.
 */
enum class PaymentStatus {
    PENDING,
    WAITING,
    SUCCESS,
    FAILED,
    ;

    val storageValue: String get() = name
}
