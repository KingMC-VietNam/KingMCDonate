package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.ReferenceCode
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `bank_payments`. The status flip to SUCCESS is a conditional
 * update run inside the confirmation transaction (so it shares one transaction
 * with the processed-tx insert and the totals upsert), and the external point
 * credit is gated by a separate conditional `reward_applied` flip so a reconcile
 * pass can credit at most once. Reference/history/reconcile/claim queries are
 * shared via [PaymentDao].
 */
class BankPaymentDao(database: Database) : PaymentDao<BankPayment>(database, "bank_payments") {

    /** Insert a fresh PENDING order and return its network-unique reference code. */
    fun insertPending(
        playerUuid: UUID,
        amount: Long,
        provider: String,
        ownerServer: String,
        now: Long,
    ): String =
        withConnection { conn ->
            retryOnCollision(REFERENCE_RETRIES) {
                val code = ReferenceCode.generate()
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
                code
            }
        }

    /** PENDING orders owned by [serverId], oldest first, for timeout. */
    fun findPendingByServer(serverId: String): List<BankPayment> =
        findByServerAndStatus(serverId, PaymentStatus.PENDING)

    /**
     * All PENDING orders network-wide, oldest first — the confirmer's gateway-match set. Unlike
     * [findPendingByServer] this ignores `owner_server`, so a single confirmer node resolves orders
     * created on any node; timeout stays owner-scoped via [findPendingByServer].
     */
    fun findPendingAllServers(): List<BankPayment> = withConnection { conn ->
        conn.prepareStatement("SELECT * FROM bank_payments WHERE status = ? ORDER BY created_at LIMIT ?").use { ps ->
            ps.setString(1, PaymentStatus.PENDING.storageValue)
            ps.setInt(2, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /**
     * The oldest PENDING order whose plain reference is contained in [haystack] and whose
     * amount equals [amount] — the webhook lookup, mirroring the poll match rule. `INSTR`
     * is used (not `LIKE`/`||`) so the containment test is portable across SQLite and MySQL;
     * both [haystack] and stored references are upper-case `[A-Z0-9]`, so case is moot.
     */
    fun findPendingByContainedReference(haystack: String, amount: Long): BankPayment? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM bank_payments WHERE status = ? AND amount = ? AND INSTR(?, reference_code) > 0 " +
                "ORDER BY created_at LIMIT 1",
        ).use { ps ->
            ps.setString(1, PaymentStatus.PENDING.storageValue)
            ps.setLong(2, amount)
            ps.setString(3, haystack)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() }.firstOrNull() }
        }
    }

    /**
     * The oldest PENDING order whose plain reference is contained in [haystack], **at any amount** —
     * for reporting a transfer that named an order but paid the wrong figure.
     *
     * Never credit from this: only [findPendingByContainedReference]'s exact-amount match may do
     * that. A transfer's text can name several orders, and the amount is what picks the right one.
     */
    fun findPendingByContainedReferenceAnyAmount(haystack: String): BankPayment? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM bank_payments WHERE status = ? AND INSTR(?, reference_code) > 0 " +
                "ORDER BY created_at LIMIT 1",
        ).use { ps ->
            ps.setString(1, PaymentStatus.PENDING.storageValue)
            ps.setString(2, haystack)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() }.firstOrNull() }
        }
    }

    /**
     * FAILED orders network-wide updated at or after [since] — added to the confirmer's match set so a
     * late transfer for a just-expired order is still surfaced, not dropped, whichever node owns it.
     */
    fun findFailedSinceAllServers(since: Long): List<BankPayment> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM bank_payments WHERE status = ? AND updated_at >= ? ORDER BY updated_at DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, PaymentStatus.FAILED.storageValue)
            ps.setLong(2, since)
            ps.setInt(3, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /**
     * Flip PENDING -> SUCCESS using the supplied [conn] so it runs inside the
     * confirmation transaction. Returns the affected row count (callers proceed
     * only on 1).
     */
    fun resolveSuccessWithinTxn(conn: Connection, referenceCode: String, point: Long, now: Long): Int =
        conn.prepareStatement(
            "UPDATE bank_payments SET status = ?, point = ?, updated_at = ? WHERE reference_code = ? AND status = ?",
        ).use { ps ->
            ps.setString(1, PaymentStatus.SUCCESS.storageValue)
            ps.setLong(2, point)
            ps.setLong(3, now)
            ps.setString(4, referenceCode)
            ps.setString(5, PaymentStatus.PENDING.storageValue)
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

    private fun findByServerAndStatus(serverId: String, status: PaymentStatus): List<BankPayment> =
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM bank_payments WHERE owner_server = ? AND status = ? ORDER BY created_at LIMIT ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, status.storageValue)
                ps.setInt(3, MAX_BATCH)
                ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
            }
        }

    override fun ResultSet.toModel() = BankPayment(
        id = getLong("id"),
        playerUuid = UUID.fromString(getString("player_uuid")),
        amount = getLong("amount"),
        referenceCode = getString("reference_code"),
        status = PaymentStatus.fromStorage(getString("status")) ?: PaymentStatus.FAILED,
        provider = getString("provider"),
        ownerServer = getString("owner_server"),
        externalRef = getString("external_ref"),
        point = getLong("point"),
        rewardApplied = getInt("reward_applied") == 1,
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}
