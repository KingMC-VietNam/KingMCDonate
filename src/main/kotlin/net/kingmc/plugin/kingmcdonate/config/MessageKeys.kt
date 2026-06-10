package net.kingmc.plugin.kingmcdonate.config

/** Keys into `messages.yml`, kept here so call sites never use raw string literals. */
object MessageKeys {
    const val NO_PERMISSION = "no-permission"
    const val PLAYER_ONLY = "player-only"
    const val UNKNOWN_COMMAND = "unknown-command"
    const val RELOAD_SUCCESS = "reload-success"
    const val RELOAD_FAILED = "reload-failed"
    const val CURRENCY_UNAVAILABLE = "currency-unavailable"

    const val CARD_CHARGING = "card-charging"
    const val CARD_SUCCESS = "card-success"
    const val CARD_FAILED = "card-failed"
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

    const val HISTORY_HEADER = "history-header"
    const val HISTORY_ENTRY = "history-entry"
    const val HISTORY_EMPTY = "history-empty"

    const val FAKECARD_USAGE = "fakecard-usage"
    const val FAKECARD_DONE = "fakecard-done"
    const val FAKECARD_PLAYER_NOT_FOUND = "fakecard-player-not-found"
}
