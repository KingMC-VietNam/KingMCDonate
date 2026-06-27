package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.Dao
import net.kingmc.plugin.kingmcdonate.database.dao.isUniqueViolation
import net.kingmc.plugin.kingmcdonate.util.Period
import java.sql.SQLException
import java.util.UUID

/**
 * Reads donation totals for milestone checks and records completions exactly once.
 * Totals sum `amount_vnd` across methods for a period bucket; completion uses a
 * `UNIQUE` insert so only one node in the cluster grants a given milestone.
 */
class MilestoneDao(database: Database) : Dao(database) {

    fun playerTotal(playerUuid: UUID, period: Period, periodKey: String): Long = withConnection { conn ->
        conn.prepareStatement(
            "SELECT COALESCE(SUM(amount_vnd), 0) FROM player_totals " +
                "WHERE player_uuid = ? AND period = ? AND period_key = ?",
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, period.name)
            ps.setString(3, periodKey)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
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

    fun completedThresholds(scope: String, subject: String, period: String, periodKey: String): Set<Long> =
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT threshold FROM milestone_completions " +
                    "WHERE scope = ? AND subject = ? AND period = ? AND period_key = ?",
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, subject)
                ps.setString(3, period)
                ps.setString(4, periodKey)
                ps.executeQuery().use { rs ->
                    val out = HashSet<Long>()
                    while (rs.next()) out.add(rs.getLong(1))
                    out
                }
            }
        }

    /** Insert the completion; returns true only for the call that wins the UNIQUE race. */
    fun claimCompletion(
        scope: String,
        subject: String,
        period: String,
        periodKey: String,
        threshold: Long,
        now: Long,
    ): Boolean = withConnection { conn ->
        try {
            conn.prepareStatement(
                "INSERT INTO milestone_completions (scope, subject, period, period_key, threshold, completed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, subject)
                ps.setString(3, period)
                ps.setString(4, periodKey)
                ps.setLong(5, threshold)
                ps.setLong(6, now)
                ps.executeUpdate() == 1
            }
        } catch (e: SQLException) {
            if (isUniqueViolation(e)) false else throw e
        }
    }

    companion object {
        const val SCOPE_PLAYER = "PLAYER"
        const val SCOPE_SERVER = "SERVER"
        const val SERVER_SUBJECT = ""
    }
}
