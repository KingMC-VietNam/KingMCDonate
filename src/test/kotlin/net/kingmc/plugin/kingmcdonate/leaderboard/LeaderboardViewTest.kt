package net.kingmc.plugin.kingmcdonate.leaderboard

import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao.Metric
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeaderboardViewTest {

    @Test
    fun `toggle metric flips amount and point`() {
        assertEquals(Metric.POINT, LeaderboardView(Metric.AMOUNT, Period.ALL).toggledMetric().metric)
        assertEquals(Metric.AMOUNT, LeaderboardView(Metric.POINT, Period.ALL).toggledMetric().metric)
    }

    @Test
    fun `toggle period cycles all, day, week, month and wraps`() {
        var v = LeaderboardView(Metric.AMOUNT, Period.ALL)
        v = v.toggledPeriod(); assertEquals(Period.DAY, v.period)
        v = v.toggledPeriod(); assertEquals(Period.WEEK, v.period)
        v = v.toggledPeriod(); assertEquals(Period.MONTH, v.period)
        v = v.toggledPeriod(); assertEquals(Period.ALL, v.period)
    }

    @Test
    fun `formats amount as money and point as a plain number`() {
        assertEquals(Text.formatMoney(100_000), LeaderboardView(Metric.AMOUNT, Period.ALL).formatValue(100_000))
        assertEquals("1500", LeaderboardView(Metric.POINT, Period.ALL).formatValue(1500))
    }

    @Test
    fun `row tokens carry rank, player, metric label and formatted value`() {
        val tokens = LeaderboardView(Metric.POINT, Period.ALL).rowTokens(2, null, 1500, "Điểm")
        assertEquals("2", tokens["rank"])
        assertEquals("?", tokens["player"]) // null name falls back
        assertEquals("Điểm", tokens["metric"])
        assertEquals("1500", tokens["value"])
    }
}
