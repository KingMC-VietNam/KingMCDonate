package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.Dialect
import java.util.UUID

/** Maps player uuid to the last-seen name, kept current on each top-up. */
class PlayerDao(database: Database) : Dao(database) {

    fun upsert(playerUuid: UUID, name: String) = withConnection { conn ->
        val sql = "INSERT INTO players (uuid, name) VALUES (?, ?) " + when (database.dialect) {
            Dialect.SQLITE -> "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name"
            Dialect.MYSQL -> "ON DUPLICATE KEY UPDATE name = VALUES(name)"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, name)
            ps.executeUpdate()
        }
    }

    /** Uuid of a player last seen under [name] (case-insensitive), or null if unknown. */
    fun findUuid(name: String): UUID? = withConnection { conn ->
        conn.prepareStatement("SELECT uuid FROM players WHERE LOWER(name) = LOWER(?) LIMIT 1").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) UUID.fromString(rs.getString("uuid")) else null }
        }
    }

    /** Last-seen name for [playerUuid], or null if unknown. */
    fun findName(playerUuid: UUID): String? = withConnection { conn ->
        conn.prepareStatement("SELECT name FROM players WHERE uuid = ?").use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("name") else null }
        }
    }

    /**
     * Atomically flip `first_topup_done` 0 -> 1, returning true only for the call that
     * wins. Ensures a row exists first so a player whose name was never recorded still
     * gets exactly one first-topup grant across the cluster.
     */
    fun claimFirstTopup(playerUuid: UUID): Boolean = withConnection { conn ->
        ensureRow(conn, playerUuid)
        conn.prepareStatement(
            "UPDATE players SET first_topup_done = 1 WHERE uuid = ? AND first_topup_done = 0",
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.executeUpdate() == 1
        }
    }

    private fun ensureRow(conn: java.sql.Connection, playerUuid: UUID) {
        val sql = "INSERT INTO players (uuid, name) VALUES (?, NULL) " + when (database.dialect) {
            Dialect.SQLITE -> "ON CONFLICT(uuid) DO NOTHING"
            Dialect.MYSQL -> "ON DUPLICATE KEY UPDATE uuid = uuid"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.executeUpdate()
        }
    }
}
