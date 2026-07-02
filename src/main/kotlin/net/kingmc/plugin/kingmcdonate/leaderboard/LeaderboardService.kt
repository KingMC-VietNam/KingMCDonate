package net.kingmc.plugin.kingmcdonate.leaderboard

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Periods
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves top boards and per-player aggregates from immutable snapshots refreshed off
 * the shared totals table. A board's snapshot is keyed by metric + period; it is
 * rebuilt asynchronously when its TTL lapses or the current period bucket rolls over,
 * and the previous snapshot is returned in the meantime so reads never block.
 */
class LeaderboardService(
    private val dao: LeaderboardDao,
    private val scheduler: Scheduler,
    private val config: () -> PluginConfig,
    private val logger: PluginLogger,
) {

    private class Snapshot<T>(val periodKey: String, val value: T, val expiresAt: Long)

    private val boards = ConcurrentHashMap<String, Snapshot<List<LeaderboardDao.Entry>>>()
    private val playerStats = ConcurrentHashMap<UUID, Snapshot<List<LeaderboardDao.Row>>>()
    private val serverTotals = ConcurrentHashMap<Period, Snapshot<Long>>()

    fun top(metric: LeaderboardDao.Metric, period: Period): List<LeaderboardDao.Entry> {
        val now = System.currentTimeMillis()
        val periodKey = Periods.key(period, now)
        val key = "${metric.name}:${period.name}"
        val current = boards[key]
        if (fresh(current, periodKey, now)) return current!!.value
        scheduler.runIo {
            try {
                val loadedAt = System.currentTimeMillis()
                val size = config().leaderboard.size
                val entries = dao.top(metric, period, periodKey, size)
                boards[key] = Snapshot(periodKey, entries, loadedAt + ttlMillis())
            } catch (e: Exception) {
                logger.error("Leaderboard refresh failed", e)
            }
        }
        return current?.value ?: emptyList()
    }

    /**
     * Like [top] but on a cache miss loads the board synchronously and caches it before
     * returning, so the first caller gets real data instead of an empty placeholder.
     * Blocks on a DB read — call off the main thread (e.g. a virtual IO thread).
     */
    fun topEager(metric: LeaderboardDao.Metric, period: Period): List<LeaderboardDao.Entry> {
        val now = System.currentTimeMillis()
        val periodKey = Periods.key(period, now)
        val key = "${metric.name}:${period.name}"
        val current = boards[key]
        if (fresh(current, periodKey, now)) return current!!.value
        return try {
            val loadedAt = System.currentTimeMillis()
            val entries = dao.top(metric, period, periodKey, config().leaderboard.size)
            boards[key] = Snapshot(periodKey, entries, loadedAt + ttlMillis())
            entries
        } catch (e: Exception) {
            logger.error("Leaderboard eager refresh failed", e)
            current?.value ?: emptyList()
        }
    }

    fun playerStat(uuid: UUID, metric: LeaderboardDao.Metric, period: Period): Long {
        val now = System.currentTimeMillis()
        val periodKey = Periods.key(period, now)
        val rows = rows(uuid, now)
        return rows.filter { it.period == period.name && it.periodKey == periodKey }
            .sumOf { if (metric == LeaderboardDao.Metric.AMOUNT) it.amountVnd else it.point }
    }

    fun methodTotal(uuid: UUID, method: String): Long {
        val now = System.currentTimeMillis()
        return rows(uuid, now).filter { it.period == Period.ALL.name && it.method == method }.sumOf { it.amountVnd }
    }

    fun serverTotal(): Long {
        val now = System.currentTimeMillis()
        val periodKey = Periods.key(Period.ALL, now)
        val current = serverTotals[Period.ALL]
        if (fresh(current, periodKey, now)) return current!!.value
        scheduler.runIo {
            try {
                val loadedAt = System.currentTimeMillis()
                val total = dao.serverTotal(Period.ALL, periodKey)
                serverTotals[Period.ALL] = Snapshot(periodKey, total, loadedAt + ttlMillis())
            } catch (e: Exception) {
                logger.error("Leaderboard refresh failed", e)
            }
        }
        return current?.value ?: 0
    }

    /** Drop only that player's cached stats; top boards and server totals refresh via TTL. */
    fun invalidatePlayer(uuid: UUID) { playerStats.remove(uuid) }

    /** Drop cached snapshots so the next read reloads; pass a uuid to also drop that player's stats. */
    fun invalidate(uuid: UUID?) {
        if (uuid != null) playerStats.remove(uuid)
        boards.clear()
        serverTotals.clear()
    }

    fun shutdown() {
        boards.clear(); playerStats.clear(); serverTotals.clear()
    }

    private fun rows(uuid: UUID, now: Long): List<LeaderboardDao.Row> {
        val current = playerStats[uuid]
        val anyKey = Periods.key(Period.DAY, now) // day bucket changes most often; use it to detect rollover
        if (current != null && now < current.expiresAt && current.periodKey == anyKey) return current.value
        scheduler.runIo {
            try {
                val loadedAt = System.currentTimeMillis()
                val loaded = dao.playerRows(uuid)
                playerStats[uuid] = Snapshot(anyKey, loaded, loadedAt + ttlMillis())
            } catch (e: Exception) {
                logger.error("Leaderboard refresh failed", e)
            }
        }
        return current?.value ?: emptyList()
    }

    private fun <T> fresh(snapshot: Snapshot<T>?, periodKey: String, now: Long): Boolean =
        snapshot != null && now < snapshot.expiresAt && snapshot.periodKey == periodKey

    private fun ttlMillis(): Long = config().leaderboard.cacheTtlSeconds * 1000
}
