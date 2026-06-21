package net.kingmc.plugin.kingmcdonate.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Text helpers: color translation and VND money formatting.
 *
 * Color is deliberately legacy-first so it works on every 1.16.5+ client
 * (Spigot included, where Adventure is not bundled):
 *  - `&`-codes are translated to section codes.
 *  - `&#RRGGBB` hex is expanded to the vanilla `§x§R§R§G§G§B§B` sequence,
 *    which 1.16+ clients render as true RGB. Gradients are made by chaining
 *    multiple hex codes by hand in the config (no gradient tag here).
 *
 * Money is formatted with a fixed grouping symbol (`.`) and `đ` suffix so the
 * output never depends on the host machine's default locale.
 */
object Text {

    private const val SECTION = '§'

    /** `&#RRGGBB` hex tokens. */
    private val HEX_PATTERN = Regex("&#([0-9A-Fa-f]{6})")

    /** Valid characters after `&` for a legacy color/format code. */
    private const val LEGACY_CODES = "0123456789abcdefklmnorABCDEFKLMNOR"

    /** Fixed VND grouping: `.` thousands separator, no decimals. */
    private val moneyFormat = ThreadLocal.withInitial {
        DecimalFormat("#,###", DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = '.' })
    }

    /**
     * Translate `&`-codes and `&#RRGGBB` hex into section-prefixed color codes.
     * Hex is expanded first so the inserted `§` characters are never re-scanned
     * as legacy codes.
     */
    fun colorize(input: String): String {
        val hexExpanded = HEX_PATTERN.replace(input) { match ->
            buildString {
                append(SECTION).append('x')
                for (c in match.groupValues[1]) append(SECTION).append(c.lowercaseChar())
            }
        }
        return translateLegacy(hexExpanded)
    }

    /** Translate every `&<code>` into `§<code>` in place. */
    private fun translateLegacy(input: String): String {
        val chars = input.toCharArray()
        var i = 0
        while (i < chars.size - 1) {
            if (chars[i] == '&' && LEGACY_CODES.indexOf(chars[i + 1]) != -1) {
                chars[i] = SECTION
                chars[i + 1] = chars[i + 1].lowercaseChar()
                i += 2
            } else {
                i++
            }
        }
        return String(chars)
    }

    /** Format a VND amount as e.g. `100.000đ` (đ = the dong currency symbol), independent of the host locale. */
    fun formatMoney(amount: Long): String = moneyFormat.get().format(amount) + "đ"
}
