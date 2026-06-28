package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.util.Period
import java.util.UUID

/** Top-board and per-player aggregate reads over `player_totals`. */
class LeaderboardDao(database: Database) : Dao(database) {

    enum class Metric(val column: String) { AMOUNT("amount_vnd"), POINT("point") }

    data class Entry(val uuid: UUID, val name: String?, val value: Long)
    data class Row(val period: String, val periodKey: String, val method: String, val amountVnd: Long, val point: Long)

    fun top(metric: Metric, period: Period, periodKey: String, limit: Int): List<Entry> = withConnection { conn ->
        val sql =
            "SELECT t.player_uuid AS uuid, p.name AS name, SUM(t.${metric.column}) AS total " +
                "FROM player_totals t LEFT JOIN players p ON p.uuid = t.player_uuid " +
                "WHERE t.period = ? AND t.period_key = ? " +
                "GROUP BY t.player_uuid, p.name HAVING total > 0 ORDER BY total DESC LIMIT ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, period.name)
            ps.setString(2, periodKey)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                rs.mapAll { Entry(UUID.fromString(getString("uuid")), getString("name"), getLong("total")) }
            }
        }
    }

    fun playerRows(playerUuid: UUID): List<Row> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT period, period_key, method, amount_vnd, point FROM player_totals WHERE player_uuid = ?",
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.executeQuery().use { rs ->
                rs.mapAll { Row(getString("period"), getString("period_key"), getString("method"), getLong("amount_vnd"), getLong("point")) }
            }
        }
    }

    fun serverTotal(period: Period, periodKey: String): Long = withConnection { conn ->
        conn.prepareStatement(
            "SELECT COALESCE(SUM(amount_vnd), 0) FROM player_totals WHERE period = ? AND period_key = ?",
        ).use { ps ->
            ps.setString(1, period.name)
            ps.setString(2, periodKey)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }
}
