package net.kingmc.plugin.kingmcdonate.util

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * Period bucketing for per-player totals. Each period maps an instant to a stable
 * key so rows accumulate into the right bucket: `ALL` is a constant, `DAY` is the
 * ISO date, `WEEK` the ISO week-year, `MONTH` the year-month. Keys use the server's
 * default time zone.
 */
enum class Period {
    ALL,
    DAY,
    WEEK,
    MONTH,
}

object Periods {

    /** Key for [period] at [epochMillis] (e.g. DAY -> "2026-06-09", WEEK -> "2026-W24"). */
    fun key(period: Period, epochMillis: Long): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (period) {
            Period.ALL -> "all"
            Period.DAY -> date.toString()
            Period.WEEK -> "%04d-W%02d".format(
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
            )
            Period.MONTH -> "%04d-%02d".format(date.year, date.monthValue)
        }
    }
}
