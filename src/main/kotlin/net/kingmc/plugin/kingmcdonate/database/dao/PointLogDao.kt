package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.model.PointLogEntry
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for the append-only `point_log` ledger. Writes are insert-only; there
 * is no update or delete path, so the record of a point change cannot be altered.
 */
class PointLogDao(database: Database) : Dao(database) {

    /** Append one ledger row for a point change. */
    fun record(entry: PointLogEntry): Unit = withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO point_log " +
                "(player_uuid, player_name, amount, method, provider, reference_code, actor, server, content, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, entry.playerUuid.toString())
            ps.setString(2, entry.playerName)
            ps.setLong(3, entry.amount)
            ps.setString(4, entry.method)
            ps.setString(5, entry.provider)
            ps.setString(6, entry.referenceCode)
            ps.setString(7, entry.actor)
            ps.setString(8, entry.server)
            ps.setString(9, entry.content)
            ps.setLong(10, entry.createdAt)
            ps.executeUpdate()
        }
    }

    /** Most recent [limit] ledger rows for a player, newest first. */
    fun findByPlayer(playerUuid: UUID, limit: Int): List<PointLogEntry> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM point_log WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.mapAll { toEntry() } }
        }
    }

    private fun ResultSet.toEntry() = PointLogEntry(
        playerUuid = UUID.fromString(getString("player_uuid")),
        playerName = getString("player_name"),
        amount = getLong("amount"),
        method = getString("method"),
        provider = getString("provider"),
        referenceCode = getString("reference_code"),
        actor = getString("actor"),
        server = getString("server") ?: "",
        content = getString("content"),
        createdAt = getLong("created_at"),
    )
}
