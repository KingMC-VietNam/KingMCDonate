package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.ReferenceCode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Persistence for `bank_payments`. The status flip to SUCCESS is a conditional
 * update run inside the confirmation transaction (so it shares one transaction
 * with the processed-tx insert and the totals upsert), and the external point
 * credit is gated by a separate conditional `reward_applied` flip so a reconcile
 * pass can credit at most once.
 */
class BankPaymentDao(database: Database) : Dao(database) {

    /** Insert a fresh PENDING order and return its network-unique reference code. */
    fun insertPending(
        playerUuid: UUID,
        amount: Long,
        provider: String,
        ownerServer: String,
        now: Long,
        prefix: String = "",
    ): String =
        withConnection { conn ->
            repeat(REFERENCE_RETRIES) {
                val code = prefix + ReferenceCode.generate()
                try {
                    conn.prepareStatement(
                        "INSERT INTO bank_payments " +
                            "(player_uuid, amount, reference_code, status, provider, owner_server, " +
                            "reward_applied, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, playerUuid.toString())
                        ps.setLong(2, amount)
                        ps.setString(3, code)
                        ps.setString(4, PaymentStatus.PENDING.storageValue)
                        ps.setString(5, provider)
                        ps.setString(6, ownerServer)
                        ps.setLong(7, now)
                        ps.setLong(8, now)
                        ps.executeUpdate()
                    }
                    return@withConnection code
                } catch (e: SQLException) {
                    if (!isUniqueViolation(e)) throw e
                }
            }
            throw IllegalStateException("Could not generate a unique reference code after $REFERENCE_RETRIES attempts")
        }

    /** Load an order by reference, whatever its status (the confirmation path branches on it). */
    fun findByReference(referenceCode: String): BankPayment? = withConnection { conn ->
        conn.prepareStatement("SELECT * FROM bank_payments WHERE reference_code = ?").use { ps ->
            ps.setString(1, referenceCode)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toBankPayment() else null }
        }
    }

    /** PENDING orders owned by [serverId], oldest first, for polling/timeout. */
    fun findPendingByServer(serverId: String): List<BankPayment> =
        findByServerAndStatus(serverId, PaymentStatus.PENDING)

    /** FAILED orders owned by [serverId] updated at or after [since] — matched so a late transfer is surfaced, not dropped. */
    fun findFailedByServerSince(serverId: String, since: Long): List<BankPayment> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM bank_payments WHERE owner_server = ? AND status = ? AND updated_at >= ? " +
                "ORDER BY updated_at DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, PaymentStatus.FAILED.storageValue)
            ps.setLong(3, since)
            ps.setInt(4, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.collect() }
        }
    }

    /** SUCCESS orders owned by [serverId] whose external credit has not been applied yet (reconcile). */
    fun findSuccessUnrewardedByServer(serverId: String): List<BankPayment> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM bank_payments WHERE owner_server = ? AND status = ? AND reward_applied = 0 " +
                "ORDER BY created_at LIMIT ?",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, PaymentStatus.SUCCESS.storageValue)
            ps.setInt(3, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.collect() }
        }
    }

    /**
     * Flip PENDING -> SUCCESS using the supplied [conn] so it runs inside the
     * confirmation transaction. Returns the affected row count (callers proceed
     * only on 1).
     */
    fun resolveSuccessWithinTxn(conn: Connection, referenceCode: String, now: Long): Int =
        conn.prepareStatement(
            "UPDATE bank_payments SET status = ?, updated_at = ? WHERE reference_code = ? AND status = ?",
        ).use { ps ->
            ps.setString(1, PaymentStatus.SUCCESS.storageValue)
            ps.setLong(2, now)
            ps.setString(3, referenceCode)
            ps.setString(4, PaymentStatus.PENDING.storageValue)
            ps.executeUpdate()
        }

    /** Mark an expired PENDING order FAILED. Returns the affected row count. */
    fun markFailed(referenceCode: String, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE bank_payments SET status = ?, updated_at = ? WHERE reference_code = ? AND status = ?",
        ).use { ps ->
            ps.setString(1, PaymentStatus.FAILED.storageValue)
            ps.setLong(2, now)
            ps.setString(3, referenceCode)
            ps.setString(4, PaymentStatus.PENDING.storageValue)
            ps.executeUpdate()
        }
    }

    /**
     * Conditionally claim the external-reward right: flip `reward_applied` 0 -> 1.
     * Returns 1 for the single winner, 0 for everyone else, so the point credit
     * runs at most once across the confirm and any reconcile pass.
     */
    fun claimRewardApplied(referenceCode: String, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE bank_payments SET reward_applied = 1, updated_at = ? WHERE reference_code = ? AND reward_applied = 0",
        ).use { ps ->
            ps.setLong(1, now)
            ps.setString(2, referenceCode)
            ps.executeUpdate()
        }
    }

    private fun findByServerAndStatus(serverId: String, status: PaymentStatus): List<BankPayment> =
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM bank_payments WHERE owner_server = ? AND status = ? ORDER BY created_at LIMIT ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, status.storageValue)
                ps.setInt(3, MAX_BATCH)
                ps.executeQuery().use { rs -> rs.collect() }
            }
        }

    private fun ResultSet.collect(): List<BankPayment> {
        val out = ArrayList<BankPayment>()
        while (next()) out.add(toBankPayment())
        return out
    }

    private fun ResultSet.toBankPayment() = BankPayment(
        id = getLong("id"),
        playerUuid = UUID.fromString(getString("player_uuid")),
        amount = getLong("amount"),
        referenceCode = getString("reference_code"),
        status = PaymentStatus.valueOf(getString("status")),
        provider = getString("provider"),
        ownerServer = getString("owner_server"),
        externalRef = getString("external_ref"),
        rewardApplied = getInt("reward_applied") == 1,
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )

    companion object {
        private const val REFERENCE_RETRIES = 5
        private const val MAX_BATCH = 200
    }
}
