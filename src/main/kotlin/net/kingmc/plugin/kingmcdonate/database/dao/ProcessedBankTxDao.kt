package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import java.sql.Connection
import java.sql.SQLException

/**
 * Records gateway transaction ids that have been processed. The UNIQUE constraint
 * on `transaction_id` is the double-credit guard: inserting it inside the
 * confirmation transaction means a transaction seen twice can only be applied once.
 */
class ProcessedBankTxDao(database: Database) : Dao(database) {

    /**
     * Insert within the confirmation transaction using the shared [conn]. Throws
     * a [SQLException] (uniqueness violation) when the transaction id is already
     * recorded, which rolls the transaction back.
     */
    fun insertWithinTxn(conn: Connection, transactionId: String, referenceCode: String, now: Long) {
        conn.prepareStatement(
            "INSERT INTO processed_bank_tx (transaction_id, reference_code, processed_at) VALUES (?, ?, ?)",
        ).use { ps ->
            ps.setString(1, transactionId)
            ps.setString(2, referenceCode)
            ps.setLong(3, now)
            ps.executeUpdate()
        }
    }

    /**
     * Record a transaction id outside any transaction; returns true on the first
     * sighting and false when it was already recorded. Used by the late-transfer
     * path to warn an admin exactly once.
     */
    fun insertIfAbsent(transactionId: String, referenceCode: String, now: Long): Boolean = withConnection { conn ->
        try {
            insertWithinTxn(conn, transactionId, referenceCode, now)
            true
        } catch (e: SQLException) {
            if (isUniqueViolation(e)) false else throw e
        }
    }
}
