package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Base class for data-access objects. Gives subclasses pooled connection access
 * and transaction handling without each one touching the pool directly.
 *
 * Never call [withConnection]/[transaction] while already holding one: the SQLite
 * pool has a single connection, so a nested borrow would block until the connection
 * timeout and then fail. Compose by running DAO calls sequentially, not nested.
 */
abstract class Dao(protected val database: Database) {

    protected fun <T> withConnection(block: (Connection) -> T): T = database.withConnection(block)

    protected fun <T> transaction(block: (Connection) -> T): T = database.transaction(block)

    /** Map every row of this result set through [map] into a list. */
    protected fun <T> ResultSet.mapAll(map: ResultSet.() -> T): List<T> {
        val out = ArrayList<T>()
        while (next()) out.add(map())
        return out
    }

    /** Run [block], retrying up to [attempts] times when it fails on a uniqueness collision. */
    protected fun <T> retryOnCollision(attempts: Int, block: () -> T): T {
        repeat(attempts) {
            try {
                return block()
            } catch (e: SQLException) {
                if (!isUniqueViolation(e)) throw e
            }
        }
        throw IllegalStateException("Could not produce a unique value after $attempts attempts")
    }
}
