package net.kingmc.plugin.kingmcdonate.placeholder

import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.util.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class KmdExpansionParseTest {

    // A thin stand-in for LeaderboardService restricted to what resolve() needs.
    private class FakeBoard : KmdExpansion.Stats {
        override fun playerStat(uuid: UUID, metric: LeaderboardDao.Metric, period: Period) =
            if (metric == LeaderboardDao.Metric.AMOUNT && period == Period.ALL) 123_000L else 0L
        override fun methodTotal(uuid: UUID, method: String) = if (method == "card") 100_000L else 23_000L
        override fun serverTotal() = 999_000L
        override fun top(metric: LeaderboardDao.Metric, period: Period) =
            listOf(LeaderboardDao.Entry(UUID.randomUUID(), "Top1", 500_000L))
        override fun endPromo() = "30/06/2026 23:59"
    }

    private val expansion = KmdExpansion.forTest(FakeBoard())
    private val uuid = UUID.randomUUID()

    @Test
    fun `total raw and formatted`() {
        assertEquals("123000", expansion.resolve(uuid, "total"))
        assertEquals("123.000đ", expansion.resolve(uuid, "total_formatted"))
    }

    @Test
    fun `method totals and server total`() {
        assertEquals("100000", expansion.resolve(uuid, "card_total"))
        assertEquals("23000", expansion.resolve(uuid, "bank_total"))
        assertEquals("999000", expansion.resolve(uuid, "server_total"))
    }

    @Test
    fun `top money name and value`() {
        assertEquals("Top1", expansion.resolve(uuid, "top_all_1_name"))
        assertEquals("500.000đ", expansion.resolve(uuid, "top_all_1_value"))
    }

    @Test
    fun `top point value is raw count not currency`() {
        // Money board formats as VND; point board must be a plain integer string.
        assertEquals("500.000đ", expansion.resolve(uuid, "top_all_1_value"))
        assertEquals("500000", expansion.resolve(uuid, "top_point_all_1_value"))
    }

    @Test
    fun `end promo and unknown`() {
        assertEquals("30/06/2026 23:59", expansion.resolve(uuid, "end_promo"))
        assertNull(expansion.resolve(uuid, "nope_nope"))
    }
}
