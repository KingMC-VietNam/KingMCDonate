package net.kingmc.plugin.kingmcdonate.api

import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.payment.ManualCreditService
import net.kingmc.plugin.kingmcdonate.util.Period
import java.util.UUID

/**
 * Thin façade over the internal read/write services. Reads hit the thread-safe
 * leaderboard caches; [giveManual] routes through the shared manual-credit service so
 * admin-issued top-ups have a single source of truth. [resolveName] maps a uuid to the
 * best-known name (injected to keep this class free of Bukkit for testing).
 */
internal class KingMCDonateAPIImpl(
    private val leaderboard: LeaderboardService,
    private val manualCredit: ManualCreditService,
    private val resolveName: (UUID) -> String,
) : KingMCDonateAPI {

    override fun getTotalVnd(uuid: UUID): Long =
        leaderboard.playerStat(uuid, LeaderboardDao.Metric.AMOUNT, Period.ALL)

    override fun getPoint(uuid: UUID): Long =
        leaderboard.playerStat(uuid, LeaderboardDao.Metric.POINT, Period.ALL)

    override fun getTop(metric: DonationMetric, period: DonationPeriod): List<TopEntry> =
        leaderboard.top(metric.toInternal(), period.toInternal())
            .mapIndexed { index, e -> TopEntry(index + 1, e.uuid, e.name, e.value) }

    override fun giveManual(uuid: UUID, method: String, amount: Long, point: Long?) {
        val bucket = when (method.lowercase()) {
            "card" -> ManualCreditService.Bucket.CARD
            "bank" -> ManualCreditService.Bucket.BANK
            else -> throw IllegalArgumentException("Unknown method '$method' (expected 'card' or 'bank')")
        }
        manualCredit.give(bucket, uuid, resolveName(uuid), amount, point)
    }

    private fun DonationMetric.toInternal(): LeaderboardDao.Metric = when (this) {
        DonationMetric.AMOUNT -> LeaderboardDao.Metric.AMOUNT
        DonationMetric.POINT -> LeaderboardDao.Metric.POINT
    }

    private fun DonationPeriod.toInternal(): Period = when (this) {
        DonationPeriod.ALL -> Period.ALL
        DonationPeriod.DAY -> Period.DAY
        DonationPeriod.WEEK -> Period.WEEK
        DonationPeriod.MONTH -> Period.MONTH
    }
}
