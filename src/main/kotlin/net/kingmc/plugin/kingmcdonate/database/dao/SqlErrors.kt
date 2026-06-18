package net.kingmc.plugin.kingmcdonate.database.dao

import java.sql.SQLException

/**
 * True when [e] represents a uniqueness/integrity-constraint violation. SQL
 * integrity violations use SQLState class 23; SQLite reports it only in the
 * message, so both are checked.
 */
internal fun isUniqueViolation(e: SQLException): Boolean {
    if (e.sqlState?.startsWith("23") == true) return true
    val message = e.message ?: return false
    return message.contains("unique", ignoreCase = true) || message.contains("constraint", ignoreCase = true)
}
