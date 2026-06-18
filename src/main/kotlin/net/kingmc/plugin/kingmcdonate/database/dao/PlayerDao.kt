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

    /** Last-seen name for [playerUuid], or null if unknown. */
    fun findName(playerUuid: UUID): String? = withConnection { conn ->
        conn.prepareStatement("SELECT name FROM players WHERE uuid = ?").use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("name") else null }
        }
    }
}
