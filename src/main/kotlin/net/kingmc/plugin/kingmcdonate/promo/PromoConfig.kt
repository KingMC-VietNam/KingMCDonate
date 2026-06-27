package net.kingmc.plugin.kingmcdonate.promo

import org.bukkit.configuration.ConfigurationSection
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Typed view of `khuyenmai.yml`. Each promotion grants a percentage bonus to the
 * points of any top-up confirmed within its [from, to] window. Times are parsed in
 * the server's default zone; an unparseable entry is skipped.
 */
class PromoConfig {

    val promotions: List<Promo>

    /** Build from a parsed list (used by tests). */
    constructor(promotions: List<Promo>) {
        this.promotions = promotions
    }

    /** Build from the `promotions:` list of `khuyenmai.yml`. */
    constructor(section: ConfigurationSection?) {
        promotions = section?.getMapList("promotions").orEmpty().mapNotNull { raw ->
            val name = raw["name"]?.toString() ?: return@mapNotNull null
            val rate = (raw["rate"] as? Number)?.toDouble() ?: return@mapNotNull null
            val from = parse(raw["from"]?.toString()) ?: return@mapNotNull null
            val to = parse(raw["to"]?.toString()) ?: return@mapNotNull null
            Promo(name, rate, from, to)
        }
    }

    data class Promo(val name: String, val ratePercent: Double, val fromMillis: Long, val toMillis: Long)

    private fun parse(text: String?): Long? = text?.let {
        try {
            LocalDateTime.parse(it.trim(), FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
