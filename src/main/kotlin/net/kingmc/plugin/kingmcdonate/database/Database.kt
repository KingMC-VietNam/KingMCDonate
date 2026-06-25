package net.kingmc.plugin.kingmcdonate.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.migration.MigrationRunner
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.io.File
import java.sql.Connection

/**
 * HikariCP-pooled access to SQLite (default) or MySQL. SQLite uses a single
 * connection (single-writer); MySQL uses a tuned pool with prepared-statement
 * caching. The pool is opened in [connect] and must be [close]d on disable.
 */
class Database(
    private val config: PluginConfig.DatabaseConfig,
    private val dataFolder: File,
    private val logger: PluginLogger,
) {

    val dialect: Dialect = Dialect.fromType(config.type)

    private lateinit var dataSource: HikariDataSource

    fun connect() {
        val hikari = HikariConfig().apply {
            poolName = "KingMCDonate-DB"
            connectionTimeout = 30_000
        }

        when (dialect) {
            Dialect.SQLITE -> {
                val file = File(dataFolder, config.sqliteFile)
                file.parentFile?.mkdirs()
                hikari.jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
                hikari.driverClassName = "org.sqlite.JDBC"
                hikari.maximumPoolSize = 1 // SQLite is single-writer; a bigger pool only serializes
                // Wait (not fail) on a held lock, and use WAL so reads don't block the writer.
                hikari.connectionInitSql = "PRAGMA busy_timeout = 5000"
                hikari.addDataSourceProperty("journal_mode", "WAL")
                hikari.addDataSourceProperty("synchronous", "NORMAL")
            }
            Dialect.MYSQL -> {
                hikari.jdbcUrl =
                    "jdbc:mysql://${config.mysqlHost}:${config.mysqlPort}/${config.mysqlDatabase}" +
                    "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                hikari.driverClassName = "com.mysql.cj.jdbc.Driver"
                hikari.username = config.mysqlUsername
                hikari.password = config.mysqlPassword
                hikari.maximumPoolSize = config.mysqlPoolSize
                hikari.addDataSourceProperty("cachePrepStmts", "true")
                hikari.addDataSourceProperty("prepStmtCacheSize", "250")
                hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
        }

        dataSource = HikariDataSource(hikari)
        logger.debug { "Database pool opened (dialect=$dialect, maxPool=${hikari.maximumPoolSize})" }
    }

    /** Run pending schema migrations to the latest version. */
    fun migrate() {
        MigrationRunner(dataSource, dialect, logger).migrate()
    }

    /** Borrow a pooled connection for [block]; the connection is always returned to the pool. */
    fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    /**
     * Run [block] in a transaction: commits on success, rolls back on any
     * exception, and restores auto-commit before returning the connection.
     */
    fun <T> transaction(block: (Connection) -> T): T = withConnection { conn ->
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun close() {
        if (this::dataSource.isInitialized && !dataSource.isClosed) {
            dataSource.close()
            logger.debug { "Database pool closed" }
        }
    }
}
