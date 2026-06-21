package net.kingmc.plugin.kingmcdonate.gui

/**
 * Parses a config slot value into concrete slot indices. Accepts a single int, a
 * range string like `"0-8"`, a comma list like `"1,3,5"`, a mixed string `"0-8,10"`,
 * or a list of any of those. Order is preserved and duplicates removed.
 */
object SlotParser {

    fun parse(raw: Any?): List<Int> = when (raw) {
        null -> emptyList()
        is Int -> listOf(raw)
        is Number -> listOf(raw.toInt())
        is String -> parseString(raw)
        is List<*> -> raw.flatMap { parse(it) }
        else -> emptyList()
    }.distinct()

    private fun parseString(value: String): List<Int> =
        value.split(",").flatMap { token ->
            val part = token.trim()
            when {
                part.isEmpty() -> emptyList()
                "-" in part -> parseRange(part)
                else -> part.toIntOrNull()?.let { listOf(it) } ?: emptyList()
            }
        }

    private fun parseRange(part: String): List<Int> {
        val bounds = part.split("-", limit = 2)
        val from = bounds[0].trim().toIntOrNull() ?: return emptyList()
        val to = bounds[1].trim().toIntOrNull() ?: return emptyList()
        return if (from <= to) (from..to).toList() else emptyList()
    }
}
