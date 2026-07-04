package net.kingmc.plugin.kingmcdonate.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.kingmc.plugin.kingmcdonate.database.migration.MigrationRunner
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class MigrationRunnerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var ds: HikariDataSource

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    @BeforeEach
    fun setUp() {
        val file = File(tempDir, "test.db")
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
            },
        )
    }

    @AfterEach
    fun tearDown() {
        ds.close()
    }

    private fun runner(dataSource: DataSource = ds) =
        MigrationRunner(dataSource, Dialect.SQLITE, logger)

    @Test
    fun `fresh database migrates to latest version and creates all core tables`() {
        runner().migrate()

        assertEquals("1", storedVersion())
        val expected = listOf(
            "config_kv", "players", "card_payments", "bank_payments",
            "processed_bank_tx", "pending_reward", "player_totals",
            "milestone_completions", "point_log",
        )
        for (table in expected) {
            // Throws if the table is missing.
            ds.connection.use { it.createStatement().executeQuery("SELECT COUNT(*) FROM $table").close() }
        }
    }

    @Test
    fun `re-running migration is idempotent`() {
        runner().migrate()
        // Insert a row, then migrate again: data survives and version is unchanged.
        ds.connection.use {
            it.createStatement().executeUpdate("INSERT INTO players (uuid, name) VALUES ('u1', 'Alice')")
        }
        runner().migrate()

        assertEquals("1", storedVersion())
        ds.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT name FROM players WHERE uuid = 'u1'").use { rs ->
                rs.next()
                assertEquals("Alice", rs.getString(1))
            }
        }
    }

    @Test
    fun `duplicate bank reference_code violates unique constraint`() {
        runner().migrate()
        insertBank("REF-DUP")
        assertThrows(SQLException::class.java) { insertBank("REF-DUP") }
    }

    @Test
    fun `duplicate processed transaction_id violates unique constraint`() {
        runner().migrate()
        insertTx("TX-DUP")
        assertThrows(SQLException::class.java) { insertTx("TX-DUP") }
    }

    @Test
    fun `players table has first_topup_done column`() {
        runner().migrate()
        ds.connection.use { conn ->
            conn.createStatement().executeUpdate(
                "INSERT INTO players (uuid, name, first_topup_done) VALUES ('u-ft', 'Bob', 1)",
            )
            conn.createStatement().executeQuery(
                "SELECT first_topup_done FROM players WHERE uuid = 'u-ft'",
            ).use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `milestone_completions enforces unique completion`() {
        runner().migrate()
        insertCompletion("PLAYER", "u1", "ALL", "all", 100000)
        assertThrows(SQLException::class.java) { insertCompletion("PLAYER", "u1", "ALL", "all", 100000) }
        // A different period_key for the same threshold is a distinct completion.
        insertCompletion("PLAYER", "u1", "DAY", "2026-06-26", 100000)
    }

    private fun insertCompletion(scope: String, subject: String, period: String, key: String, threshold: Long) {
        ds.connection.use {
            it.createStatement().executeUpdate(
                "INSERT INTO milestone_completions " +
                    "(scope, subject, period, period_key, threshold, completed_at) " +
                    "VALUES ('$scope', '$subject', '$period', '$key', $threshold, 0)",
            )
        }
    }

    private fun insertBank(ref: String) {
        ds.connection.use {
            it.createStatement().executeUpdate(
                "INSERT INTO bank_payments " +
                    "(player_uuid, amount, reference_code, status, provider, owner_server, created_at, updated_at) " +
                    "VALUES ('u1', 10000, '$ref', 'PENDING', 'sepay', 'node-a', 0, 0)",
            )
        }
    }

    private fun insertTx(txId: String) {
        ds.connection.use {
            it.createStatement().executeUpdate(
                "INSERT INTO processed_bank_tx (transaction_id, reference_code, processed_at) " +
                    "VALUES ('$txId', 'REF', 0)",
            )
        }
    }

    private fun storedVersion(): String? {
        ds.connection.use { conn ->
            conn.createStatement()
                .executeQuery("SELECT config_value FROM config_kv WHERE config_key = 'migration_version'")
                .use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }
}
