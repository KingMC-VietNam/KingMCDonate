package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.Dialect
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Periods
import java.util.UUID

/**
 * Accumulates per-player donation totals. Each successful payment adds its VNĐ
 * amount and granted points to every period bucket (ALL/DAY/WEEK/MONTH) for the
 * given method, via a dialect-specific upsert that increments existing rows.
 */
class PlayerTotalsDao(database: Database) : Dao(database) {

    /** Add [amountVnd] and [point] to all period buckets for [method] at [now]. */
    fun add(playerUuid: UUID, method: String, amountVnd: Long, point: Long, now: Long) = transaction { conn ->
        val sql = upsertSql(database.dialect)
        conn.prepareStatement(sql).use { ps ->
            for (period in Period.entries) {
                ps.setString(1, playerUuid.toString())
                ps.setString(2, period.name)
                ps.setString(3, Periods.key(period, now))
                ps.setString(4, method)
                ps.setLong(5, amountVnd)
                ps.setLong(6, point)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun upsertSql(dialect: Dialect): String {
        val insert = "INSERT INTO player_totals " +
            "(player_uuid, period, period_key, method, amount_vnd, point) VALUES (?, ?, ?, ?, ?, ?) "
        return insert + when (dialect) {
            Dialect.SQLITE ->
                "ON CONFLICT(player_uuid, period, period_key, method) DO UPDATE SET " +
                    "amount_vnd = amount_vnd + excluded.amount_vnd, point = point + excluded.point"
            Dialect.MYSQL ->
                "ON DUPLICATE KEY UPDATE " +
                    "amount_vnd = amount_vnd + VALUES(amount_vnd), point = point + VALUES(point)"
        }
    }
}
