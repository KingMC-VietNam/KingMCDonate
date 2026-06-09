package net.kingmc.plugin.kingmcdonate.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

class PeriodsTest {

    private val epoch = 1_700_000_000_000L // 2023-11-14
    private val date = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()

    @Test
    fun `all period is a constant key`() {
        assertEquals("all", Periods.key(Period.ALL, epoch))
    }

    @Test
    fun `day period is the iso date in the server zone`() {
        assertEquals(date.toString(), Periods.key(Period.DAY, epoch))
    }

    @Test
    fun `month period is year and month`() {
        assertEquals("%04d-%02d".format(date.year, date.monthValue), Periods.key(Period.MONTH, epoch))
    }

    @Test
    fun `week period is iso week-based year`() {
        val expected = "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        assertEquals(expected, Periods.key(Period.WEEK, epoch))
    }
}
