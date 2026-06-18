package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.PendingReward
import java.util.UUID

/**
 * The multi-node reward outbox (`pending_reward`). Delivery is claimed atomically
 * (`SET claimed_by WHERE claimed_by IS NULL`) so only one node runs a row; a
 * stale-claim reaper requeues rows a dead claimer never delivered.
 */
class PendingRewardDao(database: Database) : Dao(database) {

    /** Enqueue a player-present reward for later delivery on whichever node has the player. */
    fun enqueue(playerUuid: UUID, referenceCode: String, payloadJson: String, now: Long) = withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO pending_reward (player_uuid, reference_code, payload, created_at, delivered) " +
                "VALUES (?, ?, ?, ?, 0)",
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, referenceCode)
            ps.setString(3, payloadJson)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
    }

    /** Unclaimed, undelivered rows (any player), capped at [limit]; the deliverer filters to local players. */
    fun findClaimable(limit: Int): List<PendingReward> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT id, player_uuid, reference_code, payload FROM pending_reward " +
                "WHERE delivered = 0 AND claimed_by IS NULL ORDER BY created_at LIMIT ?",
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> rs.toRewards() }
        }
    }

    /** Unclaimed, undelivered rows for any of [playerUuids]; empty when the input is empty. */
    fun findClaimableFor(playerUuids: Collection<UUID>): List<PendingReward> {
        if (playerUuids.isEmpty()) return emptyList()
        val placeholders = playerUuids.joinToString(",") { "?" }
        return withConnection { conn ->
            conn.prepareStatement(
                "SELECT id, player_uuid, reference_code, payload FROM pending_reward " +
                    "WHERE delivered = 0 AND claimed_by IS NULL AND player_uuid IN ($placeholders)",
            ).use { ps ->
                playerUuids.forEachIndexed { i, uuid -> ps.setString(i + 1, uuid.toString()) }
                ps.executeQuery().use { rs -> rs.toRewards() }
            }
        }
    }

    private fun java.sql.ResultSet.toRewards(): List<PendingReward> {
        val out = ArrayList<PendingReward>()
        while (next()) {
            out.add(
                PendingReward(
                    id = getLong("id"),
                    playerUuid = UUID.fromString(getString("player_uuid")),
                    referenceCode = getString("reference_code"),
                    payload = getString("payload"),
                ),
            )
        }
        return out
    }

    /** Atomically claim row [id] for [node]; returns 1 for the single winner, 0 otherwise. */
    fun claim(id: Long, node: String, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE pending_reward SET claimed_by = ?, claimed_at = ? WHERE id = ? AND claimed_by IS NULL",
        ).use { ps ->
            ps.setString(1, node)
            ps.setLong(2, now)
            ps.setLong(3, id)
            ps.executeUpdate()
        }
    }

    fun markDelivered(id: Long): Int = withConnection { conn ->
        conn.prepareStatement("UPDATE pending_reward SET delivered = 1 WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    /** Clear claims older than [thresholdMillis] that were never delivered; returns the requeued count. */
    fun reapStale(thresholdMillis: Long, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE pending_reward SET claimed_by = NULL, claimed_at = NULL " +
                "WHERE delivered = 0 AND claimed_by IS NOT NULL AND claimed_at < ?",
        ).use { ps ->
            ps.setLong(1, now - thresholdMillis)
            ps.executeUpdate()
        }
    }
}
