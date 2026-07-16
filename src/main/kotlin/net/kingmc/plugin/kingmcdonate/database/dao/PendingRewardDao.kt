package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.reward.PendingReward
import java.util.UUID

/**
 * The multi-node reward outbox (`pending_reward`). Delivery is at-most-once: [claimAndDeliver]
 * claims a row and marks it delivered in one statement, so no row's payload can run on two
 * nodes or run twice after a crash. There is no reaper — a crash between the mark and the
 * dispatch loses that one reward rather than replaying its reward commands.
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
            ps.executeQuery().use { rs -> rs.mapAll { toPendingReward() } }
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
                ps.executeQuery().use { rs -> rs.mapAll { toPendingReward() } }
            }
        }
    }

    private fun java.sql.ResultSet.toPendingReward() = PendingReward(
        id = getLong("id"),
        playerUuid = UUID.fromString(getString("player_uuid")),
        referenceCode = getString("reference_code"),
        payload = getString("payload"),
    )

    /**
     * Atomically claim row [id] for [node] **and** mark it delivered, in one statement; returns 1
     * for the single winner, 0 otherwise. Marking before the payload runs is what makes delivery
     * at-most-once: only the winner may dispatch, and no later pass can hand the row to anyone else.
     */
    fun claimAndDeliver(id: Long, node: String, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE pending_reward SET delivered = 1, claimed_by = ?, claimed_at = ? WHERE id = ? AND delivered = 0",
        ).use { ps ->
            ps.setString(1, node)
            ps.setLong(2, now)
            ps.setLong(3, id)
            ps.executeUpdate()
        }
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
