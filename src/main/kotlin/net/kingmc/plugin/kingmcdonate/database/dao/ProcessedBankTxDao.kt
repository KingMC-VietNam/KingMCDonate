package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import java.sql.Connection
import java.sql.SQLException

/**
 * Records gateway transaction ids that have been processed. The UNIQUE constraint
 * on `transaction_id` is the double-credit guard: inserting it inside the
 * confirmation transaction means a transaction seen twice can only be applied once.
 *
 * Because that key *is* the guard, anything else recording a "we already dealt with this"
 * marker must keep out of its namespace — a marker stored under the bare tx id would collide
 * with [insertWithinTxn] and roll back the very credit it was meant to be independent of.
 * [mismatchKey] is that separate namespace; see [net.kingmc.plugin.kingmcdonate.payment.bank.BankConfirmService.reportUnmatched].
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

    companion object {
        /**
         * Key a "already warned about this transfer" marker away from the credit guard's bare tx id.
         * Recording the raw id would make a later [insertWithinTxn] for the same transaction violate
         * UNIQUE and roll its confirmation back — permanently losing a credit the marker had nothing
         * to do with, since one transfer's text can name several orders.
         */
        fun mismatchKey(transactionId: String) = "mismatch:$transactionId"
    }
}
