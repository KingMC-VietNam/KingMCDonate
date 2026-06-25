package net.kingmc.plugin.kingmcdonate.database.migration

import net.kingmc.plugin.kingmcdonate.database.Dialect
import java.sql.Connection

/**
 * Initial schema. Money is stored in VND as BIGINT; timestamps as epoch-millis
 * BIGINT; booleans as INT (0/1) for portability. All statements are
 * `CREATE TABLE IF NOT EXISTS` so a partial pre-existing schema is tolerated.
 */
class Migration0001 : Migration {

    override val version = 1

    override fun apply(connection: Connection, dialect: Dialect) {
        val id = dialect.autoIncrementPk
        val statements = listOf(
            // key-value store (migration_version lives here)
            """
            CREATE TABLE IF NOT EXISTS config_kv (
                config_key   VARCHAR(191) PRIMARY KEY,
                config_value VARCHAR(255)
            )
            """,
            // uuid <-> name
            """
            CREATE TABLE IF NOT EXISTS players (
                uuid VARCHAR(36) PRIMARY KEY,
                name VARCHAR(16)
            )
            """,
            // card top-ups
            """
            CREATE TABLE IF NOT EXISTS card_payments (
                id             $id,
                player_uuid    VARCHAR(36) NOT NULL,
                player_name    VARCHAR(16),
                card_type      VARCHAR(32) NOT NULL,
                amount         BIGINT NOT NULL,
                serial         VARCHAR(64),
                pin            VARCHAR(64),
                status         VARCHAR(16) NOT NULL,
                reference_code VARCHAR(32) NOT NULL UNIQUE,
                card_provider  VARCHAR(32),
                transaction_id VARCHAR(128),
                owner_server   VARCHAR(64) NOT NULL DEFAULT '',
                point          BIGINT NOT NULL DEFAULT 0,
                reward_applied INT NOT NULL DEFAULT 0,
                created_at     BIGINT NOT NULL,
                updated_at     BIGINT NOT NULL
            )
            """,
            // bank top-ups (multi-server aware)
            """
            CREATE TABLE IF NOT EXISTS bank_payments (
                id             $id,
                player_uuid    VARCHAR(36) NOT NULL,
                amount         BIGINT NOT NULL,
                reference_code VARCHAR(32) NOT NULL UNIQUE,
                status         VARCHAR(16) NOT NULL,
                provider       VARCHAR(32) NOT NULL,
                owner_server   VARCHAR(64) NOT NULL,
                external_ref   VARCHAR(128),
                reward_applied INT NOT NULL DEFAULT 0,
                created_at     BIGINT NOT NULL,
                updated_at     BIGINT NOT NULL
            )
            """,
            // processed gateway transactions (double-credit guard)
            """
            CREATE TABLE IF NOT EXISTS processed_bank_tx (
                id             $id,
                transaction_id VARCHAR(128) NOT NULL UNIQUE,
                reference_code VARCHAR(32) NOT NULL,
                processed_at   BIGINT NOT NULL
            )
            """,
            // outbox for rewards that need the player present
            """
            CREATE TABLE IF NOT EXISTS pending_reward (
                id             $id,
                player_uuid    VARCHAR(36) NOT NULL,
                reference_code VARCHAR(32) NOT NULL,
                payload        TEXT NOT NULL,
                created_at     BIGINT NOT NULL,
                claimed_by     VARCHAR(64),
                claimed_at     BIGINT,
                delivered      INT NOT NULL DEFAULT 0
            )
            """,
            // per-period, per-method totals: amount_vnd + point
            """
            CREATE TABLE IF NOT EXISTS player_totals (
                player_uuid VARCHAR(36) NOT NULL,
                period      VARCHAR(8)  NOT NULL,
                period_key  VARCHAR(16) NOT NULL,
                method      VARCHAR(8)  NOT NULL,
                amount_vnd  BIGINT NOT NULL DEFAULT 0,
                point       BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid, period, period_key, method)
            )
            """,
        )

        // Secondary indexes for the hot lookup paths: poll sweeps filter by (owner_server, status),
        // history reads by player, and the reward outbox drains undelivered/unclaimed rows.
        // MySQL has no `CREATE INDEX IF NOT EXISTS`; migrations run once per version so plain DDL is safe.
        val ifNotExists = if (dialect == Dialect.SQLITE) "IF NOT EXISTS " else ""
        val indexes = listOf(
            "CREATE INDEX ${ifNotExists}idx_card_payments_server_status ON card_payments (owner_server, status)",
            "CREATE INDEX ${ifNotExists}idx_card_payments_player ON card_payments (player_uuid, created_at)",
            "CREATE INDEX ${ifNotExists}idx_bank_payments_server_status ON bank_payments (owner_server, status)",
            "CREATE INDEX ${ifNotExists}idx_bank_payments_player ON bank_payments (player_uuid, created_at)",
            "CREATE INDEX ${ifNotExists}idx_pending_reward_pending ON pending_reward (delivered, claimed_by)",
        )

        connection.createStatement().use { stmt ->
            for (sql in statements) stmt.executeUpdate(sql.trimIndent())
            for (sql in indexes) stmt.executeUpdate(sql)
        }
    }
}
