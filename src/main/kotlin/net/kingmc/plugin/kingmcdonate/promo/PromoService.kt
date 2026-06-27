package net.kingmc.plugin.kingmcdonate.promo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Resolves the active promotion bonus. When several promotions overlap the current
 * instant, the highest rate wins. The bonus is applied to base points only — never
 * to the VND amount.
 */
class PromoService(private val config: () -> PromoConfig) {

    private fun active(now: Long): PromoConfig.Promo? =
        config().promotions
            .filter { now in it.fromMillis..it.toMillis }
            .maxByOrNull { it.ratePercent }

    fun activeBonusPercent(now: Long): Double = active(now)?.ratePercent ?: 0.0

    fun activeEnd(now: Long): Long? = active(now)?.toMillis

    /** base * (1 + rate/100), rounded half-up. */
    fun applyBonus(basePoint: Long, now: Long): Long {
        val percent = activeBonusPercent(now)
        if (percent == 0.0) return basePoint
        return Math.round(basePoint * (1.0 + percent / 100.0))
    }

    /** End time of the active promotion as `dd/MM/yyyy HH:mm`, or empty when none is active. */
    fun endPromoFormatted(now: Long): String {
        val end = activeEnd(now) ?: return ""
        return DISPLAY.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(end))
    }

    companion object {
        private val DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
