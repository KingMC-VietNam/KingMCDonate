package net.kingmc.plugin.kingmcdonate.payment.model

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

    companion object {
        /** Parse a stored status, returning null for any unrecognised value (so one bad row never aborts a batch). */
        fun fromStorage(value: String?): PaymentStatus? = entries.firstOrNull { it.storageValue == value }
    }
}
