package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import java.sql.Connection

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
}
