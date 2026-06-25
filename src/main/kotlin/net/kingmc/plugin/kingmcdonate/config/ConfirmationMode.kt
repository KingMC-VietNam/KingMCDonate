package net.kingmc.plugin.kingmcdonate.config

/**
 * How a subsystem actively confirms payments. Governs only active confirmation —
 * order-expiry timeout, startup resume and reconcile (housekeeping) run in every
 * mode. Unknown config values fall back to [POLL].
 */
enum class ConfirmationMode {
    POLL,
    WEBHOOK,
    BOTH,
    ;

    /** True when the gateway should be actively polled. */
    val pollsGateway: Boolean get() = this == POLL || this == BOTH

    /** True when an inbound webhook handler should be registered. */
    val usesWebhook: Boolean get() = this == WEBHOOK || this == BOTH

    companion object {
        fun parse(value: String?): ConfirmationMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: POLL
    }
}
