package net.kingmc.plugin.kingmcdonate.gui.menu

/**
 * A parsed click action: a [verb] and its [arg]. The wire form is `verb:argument`
 * (e.g. `open:card-type`, `console:say hi`, `page:next`); a bare token with no colon
 * (e.g. `close`) parses to that verb with an empty arg. Parsing is pure and tested.
 */
data class MenuAction(val verb: String, val arg: String) {

    companion object {
        fun parse(raw: String): MenuAction {
            val trimmed = raw.trim()
            val colon = trimmed.indexOf(':')
            return if (colon < 0) {
                MenuAction(trimmed.lowercase(), "")
            } else {
                MenuAction(trimmed.substring(0, colon).trim().lowercase(), trimmed.substring(colon + 1).trim())
            }
        }
    }
}
