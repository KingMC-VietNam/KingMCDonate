package net.kingmc.plugin.kingmcdonate.config

/** Keys into `messages.yml`, kept here so call sites never use raw string literals. */
object MessageKeys {
    const val NO_PERMISSION = "no-permission"
    const val PLAYER_ONLY = "player-only"
    const val UNKNOWN_COMMAND = "unknown-command"
    const val RELOAD_SUCCESS = "reload-success"
    const val RELOAD_FAILED = "reload-failed"
    const val CURRENCY_UNAVAILABLE = "currency-unavailable"

    const val STATUS_SUCCESS = "status-success"
    const val STATUS_FAILED = "status-failed"
    const val STATUS_WAITING = "status-waiting"
    const val STATUS_PENDING = "status-pending"

    const val CARD_CHARGING = "card-charging"
    const val CARD_SUCCESS = "card-success"
    const val CARD_FAILED = "card-failed"
    const val CARD_REASON_GENERIC = "card-reason-generic"
    const val CARD_REASON_TIMEOUT = "card-reason-timeout"
    const val CARD_WRONG_DENOMINATION = "card-wrong-denomination"
    const val CARD_INVALID_TYPE = "card-invalid-type"
    const val CARD_INVALID_DENOMINATION = "card-invalid-denomination"
    const val CARD_SERIAL_TOO_LONG = "card-serial-too-long"
    const val CARD_PIN_TOO_LONG = "card-pin-too-long"
    const val CARD_UNAVAILABLE = "card-unavailable"
    const val CARD_MAINTENANCE = "card-maintenance"
    const val CARD_INPUT_SERIAL = "card-input-serial"
    const val CARD_INPUT_PIN = "card-input-pin"
    const val CARD_INPUT_CANCELLED = "card-input-cancelled"
    const val ANVIL_SERIAL_TITLE = "anvil-serial-title"
    const val ANVIL_PIN_TITLE = "anvil-pin-title"

    const val BANK_CREATED = "bank-created"
    const val BANK_SUCCESS = "bank-success"
    const val BANK_EXPIRED = "bank-expired"
    const val BANK_MAINTENANCE = "bank-maintenance"
    const val BANK_UNAVAILABLE = "bank-unavailable"
    const val BANK_AMOUNT_RANGE = "bank-amount-range"

    const val HISTORY_HEADER = "history-header"
    const val HISTORY_ENTRY = "history-entry"
    const val HISTORY_EMPTY = "history-empty"
    const val HISTORY_BANK_LABEL = "history-bank-label"

    const val FAKECARD_USAGE = "fakecard-usage"
    const val FAKECARD_DONE = "fakecard-done"
    const val FAKECARD_PLAYER_NOT_FOUND = "fakecard-player-not-found"

    const val BANK_USAGE = "bank-usage"
    const val FAKEBANK_USAGE = "fakebank-usage"
    const val FAKEBANK_DONE = "fakebank-done"

    const val BOSSBAR_DISABLED = "bossbar-disabled"
    const val BOSSBAR_SHOWN = "bossbar-shown"
    const val BOSSBAR_HIDDEN = "bossbar-hidden"
    const val TOPNAP_HEADER = "topnap-header"
    const val TOPNAP_ENTRY = "topnap-entry"
    const val TOPNAP_EMPTY = "topnap-empty"
    const val MILESTONE_REACHED = "milestone-reached"
}
