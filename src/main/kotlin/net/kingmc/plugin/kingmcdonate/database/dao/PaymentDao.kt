package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import java.sql.ResultSet
import java.util.UUID

/**
 * Shared persistence for the card and bank payment tables. Both keep a `reference_code`,
 * `owner_server`, `status`, `point` and `reward_applied` column, so the reference lookup,
 * per-player history, unrewarded-SUCCESS reconcile query and the conditional `reward_applied`
 * claim are identical bar the table name and the row mapper. Table-specific work (insert,
 * the status flip whose allowed source states differ, and the poll/timeout finders) stays in
 * the subclasses.
 */
abstract class PaymentDao<T>(database: Database, protected val table: String) : Dao(database) {

    /** Map the current row into the concrete payment model. */
    protected abstract fun ResultSet.toModel(): T

    /** Load an order by reference, whatever its status (the confirmation path branches on it). */
    fun findByReference(referenceCode: String): T? = withConnection { conn ->
        conn.prepareStatement("SELECT * FROM $table WHERE reference_code = ?").use { ps ->
            ps.setString(1, referenceCode)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toModel() else null }
        }
    }

    /** Most recent [limit] orders for a player, newest first (history). */
    fun findByPlayer(playerUuid: UUID, limit: Int): List<T> = withConnection { conn ->
        conn.prepareStatement("SELECT * FROM $table WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?").use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /** SUCCESS orders owned by [serverId] whose external credit has not been applied yet (reconcile). */
    fun findSuccessUnrewardedByServer(serverId: String): List<T> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM $table WHERE owner_server = ? AND status = ? AND reward_applied = 0 " +
                "ORDER BY created_at LIMIT ?",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, PaymentStatus.SUCCESS.storageValue)
            ps.setInt(3, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /**
     * SUCCESS orders network-wide whose credit has not been applied, owned by a node other than
     * [serverId] — the awaiting-credit set no one is sweeping.
     *
     * [findSuccessUnrewardedByServer] is owner-scoped, so an order left awaiting its credit (the
     * currency backend was down when it resolved) is only ever retried by its owner. If that node
     * dies for good, nothing credits it and nothing reports it: it is SUCCESS, so the stranded-card
     * finder ignores it, and `reward_applied = 0`, so the lost-credit finder ignores it too.
     */
    fun findUnrewardedOnOtherServers(serverId: String, limit: Int): List<T> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM $table WHERE owner_server <> ? AND status = ? AND reward_applied = 0 " +
                "ORDER BY created_at DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, PaymentStatus.SUCCESS.storageValue)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /**
     * SUCCESS orders that claimed the reward but have no ledger row to show for it. The gate claims
     * `reward_applied` before crediting (at-most-once: never double-pay), so a node that dies in
     * between leaves a row that looks exactly like a paid one — the reconcile pass only looks for
     * `reward_applied = 0` and will never revisit it. The ledger, written at the end of the credit
     * path, is the only trace such an order leaves.
     *
     * Reports candidates, not proof, and it is not exhaustive. Known limits, which the caller must
     * state: an order whose ledger write itself failed appears here although the player was paid;
     * orders resolved before the ledger became credit-gated cannot be judged at all; and a credit
     * that the currency backend accepted and then failed *asynchronously* is invisible — the real
     * providers hand the credit to the main thread and return, so the row is booked either way.
     */
    fun findLostCredits(limit: Int): List<T> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT p.* FROM $table p WHERE p.status = ? AND p.reward_applied = 1 " +
                "AND NOT EXISTS (SELECT 1 FROM point_log l WHERE l.reference_code = p.reference_code) " +
                "ORDER BY p.created_at DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, PaymentStatus.SUCCESS.storageValue)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /**
     * Conditionally claim the external-reward right: flip `reward_applied` 0 -> 1. Returns 1 for
     * the single winner, 0 otherwise, so the point credit runs at most once across the resolving
     * caller and any reconcile pass.
     */
    fun claimRewardApplied(referenceCode: String, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE $table SET reward_applied = 1, updated_at = ? WHERE reference_code = ? AND reward_applied = 0",
        ).use { ps ->
            ps.setLong(1, now)
            ps.setString(2, referenceCode)
            ps.executeUpdate()
        }
    }

    companion object {
        const val REFERENCE_RETRIES = 5
        const val MAX_BATCH = 200
    }
}
