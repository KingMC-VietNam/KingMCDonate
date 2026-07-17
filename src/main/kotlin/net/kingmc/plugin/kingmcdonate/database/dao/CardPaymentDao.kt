package net.kingmc.plugin.kingmcdonate.database.dao

import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.ReferenceCode
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `card_payments`. Resolution is done with a conditional update
 * guarded by the current status so a charge can never be rewarded twice; inserts
 * regenerate the reference code on a uniqueness collision rather than reusing one.
 * Reference/history/reconcile/claim queries are shared via [PaymentDao].
 */
class CardPaymentDao(database: Database) : PaymentDao<CardPayment>(database, "card_payments") {

    /**
     * Insert a fresh PENDING record and return its unique reference code. Retries
     * with a new code if the generated one collides with an existing row.
     */
    fun insertPending(
        playerUuid: UUID,
        playerName: String,
        cardType: String,
        amount: Long,
        serial: String,
        pin: String,
        provider: String,
        ownerServer: String,
        now: Long,
    ): String = withConnection { conn ->
        retryOnCollision(REFERENCE_RETRIES) {
            val code = ReferenceCode.generate()
            conn.prepareStatement(
                "INSERT INTO card_payments " +
                    "(player_uuid, player_name, card_type, amount, serial, pin, status, " +
                    "reference_code, card_provider, owner_server, point, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
            ).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, playerName)
                ps.setString(3, cardType)
                ps.setLong(4, amount)
                ps.setString(5, serial)
                ps.setString(6, pin)
                ps.setString(7, PaymentStatus.PENDING.storageValue)
                ps.setString(8, code)
                ps.setString(9, provider)
                ps.setString(10, ownerServer)
                ps.setLong(11, now)
                ps.setLong(12, now)
                ps.executeUpdate()
            }
            code
        }
    }

    /** Move PENDING -> WAITING and store the gateway transaction handle. */
    fun markWaiting(referenceCode: String, transactionId: String?, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE card_payments SET status = ?, transaction_id = ?, updated_at = ? " +
                "WHERE reference_code = ? AND status = ?",
        ).use { ps ->
            ps.setString(1, PaymentStatus.WAITING.storageValue)
            ps.setString(2, transactionId)
            ps.setLong(3, now)
            ps.setString(4, referenceCode)
            ps.setString(5, PaymentStatus.PENDING.storageValue)
            ps.executeUpdate()
        }
    }

    /**
     * Conditionally resolve a non-terminal order to [status], setting the granted
     * [point]. Returns the affected row count: callers reward only when it is 1.
     */
    fun resolve(referenceCode: String, status: PaymentStatus, point: Long, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE card_payments SET status = ?, point = ?, updated_at = ? " +
                "WHERE reference_code = ? AND status IN (?, ?)",
        ).use { ps ->
            ps.setString(1, status.storageValue)
            ps.setLong(2, point)
            ps.setLong(3, now)
            ps.setString(4, referenceCode)
            ps.setString(5, PaymentStatus.PENDING.storageValue)
            ps.setString(6, PaymentStatus.WAITING.storageValue)
            ps.executeUpdate()
        }
    }

    /**
     * Flip a non-terminal order to SUCCESS with its granted [point] using the supplied
     * [conn] so it runs inside the confirmation transaction (shared with the totals
     * upsert). Returns the affected row count; callers proceed only on 1.
     */
    fun resolveSuccessWithinTxn(conn: Connection, referenceCode: String, point: Long, now: Long): Int =
        conn.prepareStatement(
            "UPDATE card_payments SET status = ?, point = ?, updated_at = ? " +
                "WHERE reference_code = ? AND status IN (?, ?)",
        ).use { ps ->
            ps.setString(1, PaymentStatus.SUCCESS.storageValue)
            ps.setLong(2, point)
            ps.setLong(3, now)
            ps.setString(4, referenceCode)
            ps.setString(5, PaymentStatus.PENDING.storageValue)
            ps.setString(6, PaymentStatus.WAITING.storageValue)
            ps.executeUpdate()
        }

    /**
     * Oldest non-terminal orders owned by [serverId], capped per pass, for poll/resume.
     *
     * PENDING is included as well as WAITING: a node that died between the synchronous PENDING
     * insert and the charge completing leaves the order there, and the poll is the only thing that
     * can reconcile it. The caller decides what to do with each status — a PENDING order still
     * inside its timeout may have a charge POST in flight and must not be touched.
     */
    fun findResolvableByServer(serverId: String): List<CardPayment> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM card_payments WHERE owner_server = ? AND status IN (?, ?) ORDER BY created_at LIMIT ?",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, PaymentStatus.PENDING.storageValue)
            ps.setString(3, PaymentStatus.WAITING.storageValue)
            ps.setInt(4, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /**
     * Open orders owned by a node **other** than [serverId], oldest first — the stranded set.
     *
     * Polling is deliberately owner-scoped, so if a node dies for good (or its `server-id` changes)
     * nothing sweeps its open orders: a charged card sits there forever. This is the finder behind
     * the manual `/kingmcdonate reconcile card` escape hatch, not an automatic takeover. Orders of
     * a live sibling node show up here too — it is the operator who knows which nodes are dead.
     */
    fun findResolvableOnOtherServers(serverId: String): List<CardPayment> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM card_payments WHERE owner_server <> ? AND status IN (?, ?) ORDER BY created_at LIMIT ?",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setString(2, PaymentStatus.PENDING.storageValue)
            ps.setString(3, PaymentStatus.WAITING.storageValue)
            ps.setInt(4, MAX_BATCH)
            ps.executeQuery().use { rs -> rs.mapAll { toModel() } }
        }
    }

    /** Hand an open order to [serverId] so this node's poll picks it up; 0 if it already resolved. */
    fun reown(referenceCode: String, serverId: String, now: Long): Int = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE card_payments SET owner_server = ?, updated_at = ? " +
                "WHERE reference_code = ? AND status IN (?, ?)",
        ).use { ps ->
            ps.setString(1, serverId)
            ps.setLong(2, now)
            ps.setString(3, referenceCode)
            ps.setString(4, PaymentStatus.PENDING.storageValue)
            ps.setString(5, PaymentStatus.WAITING.storageValue)
            ps.executeUpdate()
        }
    }

    override fun ResultSet.toModel() = CardPayment(
        id = getLong("id"),
        playerUuid = UUID.fromString(getString("player_uuid")),
        playerName = getString("player_name"),
        cardType = getString("card_type"),
        amount = getLong("amount"),
        serial = getString("serial"),
        pin = getString("pin"),
        status = PaymentStatus.fromStorage(getString("status")) ?: PaymentStatus.FAILED,
        referenceCode = getString("reference_code"),
        cardProvider = getString("card_provider") ?: "",
        transactionId = getString("transaction_id"),
        ownerServer = getString("owner_server") ?: "",
        point = getLong("point"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}
