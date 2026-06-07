package net.kingmc.plugin.kingmcdonate.database.migration

import net.kingmc.plugin.kingmcdonate.database.Dialect
import java.sql.Connection

/**
 * One ordered schema migration. [apply] runs inside a transaction managed by the
 * [MigrationRunner]; it must not commit or change auto-commit itself.
 */
interface Migration {
    val version: Int
    fun apply(connection: Connection, dialect: Dialect)
}
