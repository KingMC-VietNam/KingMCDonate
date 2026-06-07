package net.kingmc.plugin.kingmcdonate.database.migration

import net.kingmc.plugin.kingmcdonate.database.Dialect
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.sql.Connection
import javax.sql.DataSource

/**
 * Versioned, idempotent migration runner. The current version is stored in
 * `config_kv.migration_version`; only migrations newer than it are applied, each
 * in its own transaction with the version bumped on commit. Re-running with no
 * newer migrations is a no-op. Decoupled from Bukkit (takes a [DataSource]) so it
 * is unit-testable against a real database.
 */
class MigrationRunner(
    private val dataSource: DataSource,
    private val dialect: Dialect,
    private val logger: PluginLogger,
    private val migrations: List<Migration> = listOf(Migration0001()),
) {

    private val ordered = migrations.sortedBy { it.version }

    fun migrate() {
        dataSource.connection.use { conn ->
            ensureConfigKv(conn)
            val current = currentVersion(conn)
            val pending = ordered.filter { it.version > current }
            if (pending.isEmpty()) {
                logger.debug { "DB schema up to date at version $current" }
                return
            }
            for (migration in pending) {
                applyOne(conn, migration)
            }
            logger.debug { "DB migrated from version $current to ${ordered.last().version}" }
        }
    }

    private fun applyOne(conn: Connection, migration: Migration) {
        conn.autoCommit = false
        try {
            migration.apply(conn, dialect)
            setVersion(conn, migration.version)
            conn.commit()
            logger.debug { "Applied migration ${migration.version}" }
        } catch (e: Exception) {
            conn.rollback()
            throw IllegalStateException("Migration ${migration.version} failed", e)
        } finally {
            conn.autoCommit = true
        }
    }

    private fun ensureConfigKv(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS config_kv (" +
                    "config_key VARCHAR(191) PRIMARY KEY, config_value VARCHAR(255))",
            )
        }
    }

    private fun currentVersion(conn: Connection): Int {
        conn.prepareStatement("SELECT config_value FROM config_kv WHERE config_key = ?").use { ps ->
            ps.setString(1, VERSION_KEY)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1)?.toIntOrNull() ?: 0 else 0
            }
        }
    }

    /** Upsert the version without dialect-specific syntax: update, else insert. */
    private fun setVersion(conn: Connection, version: Int) {
        conn.prepareStatement("UPDATE config_kv SET config_value = ? WHERE config_key = ?").use { ps ->
            ps.setString(1, version.toString())
            ps.setString(2, VERSION_KEY)
            if (ps.executeUpdate() > 0) return
        }
        conn.prepareStatement("INSERT INTO config_kv (config_key, config_value) VALUES (?, ?)").use { ps ->
            ps.setString(1, VERSION_KEY)
            ps.setString(2, version.toString())
            ps.executeUpdate()
        }
    }

    companion object {
        private const val VERSION_KEY = "migration_version"
    }
}
