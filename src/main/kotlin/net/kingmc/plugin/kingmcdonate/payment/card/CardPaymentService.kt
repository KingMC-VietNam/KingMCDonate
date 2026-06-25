package net.kingmc.plugin.kingmcdonate.payment.card

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardCommands
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.provider.card.CardOutcome
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardRequest
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Orchestrates the card top-up lifecycle: validate, create a PENDING record, submit
 * to the active gateway off-thread, and resolve the outcome idempotently. Success is
 * granted only when a conditional status update affects exactly one row and the
 * gateway-recognised amount matches the declared denomination.
 */
class CardPaymentService(
    private val database: Database,
    private val cardPaymentDao: CardPaymentDao,
    private val playerTotalsDao: PlayerTotalsDao,
    private val playerDao: PlayerDao,
    private val currency: CurrencyRegistry,
    private val providers: CardProviderRegistry,
    private val rewardSink: RewardSink,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) {

    private class NotResolvableException : RuntimeException()

    /** Validate and start a card top-up for an online [player]. */
    fun submit(player: Player, type: CardType, declaredAmount: Long, serial: String, pin: String) {
        if (config().card.maintenance) {
            messages().send(player, MessageKeys.CARD_MAINTENANCE)
            return
        }
        if (!providers.isAvailable) {
            messages().send(player, MessageKeys.CARD_UNAVAILABLE)
            return
        }
        if (!currency.active.isAvailable()) {
            messages().send(player, MessageKeys.CURRENCY_UNAVAILABLE)
            return
        }
        if (type !in providers.active.supportedTypes()) {
            messages().send(player, MessageKeys.CARD_INVALID_TYPE)
            return
        }
        if (declaredAmount !in config().card.denominations) {
            messages().send(player, MessageKeys.CARD_INVALID_DENOMINATION)
            return
        }
        if (serial.isBlank() || serial.length > MAX_FIELD_LENGTH) {
            messages().send(player, MessageKeys.CARD_SERIAL_TOO_LONG)
            return
        }
        if (pin.isBlank() || pin.length > MAX_FIELD_LENGTH) {
            messages().send(player, MessageKeys.CARD_PIN_TOO_LONG)
            return
        }

        val uuid = player.uniqueId
        val name = player.name
        val provider = providers.active
        val serverId = config().serverId
        val now = System.currentTimeMillis()

        playerDao.upsert(uuid, name)
        val referenceCode = cardPaymentDao.insertPending(
            uuid, name, type.name, declaredAmount, serial, pin, provider.name, serverId, now,
        )
        messages().send(player, MessageKeys.CARD_CHARGING)
        logger.debug { "Card submit ref=$referenceCode uuid=$uuid type=$type amount=$declaredAmount provider=${provider.name}" }

        val request = CardRequest(uuid, type, declaredAmount, serial, pin, referenceCode)
        scheduler.runIo {
            try {
                val outcome = provider.submit(request)
                applyOutcome(referenceCode, uuid, name, declaredAmount, outcome)
            } catch (e: Exception) {
                // The card may already be charged (response lost); keep it WAITING so the poll service reconciles it.
                logger.error("Card submit failed ref=$referenceCode uuid=$uuid; left WAITING for the poll service.", e)
                cardPaymentDao.markWaiting(referenceCode, null, System.currentTimeMillis())
            }
        }
    }

    /** Simulate a successful charge (admin test): record then run the full reward path. */
    fun simulateSuccess(uuid: UUID, name: String, amount: Long) {
        scheduler.runIo {
            val now = System.currentTimeMillis()
            val referenceCode = cardPaymentDao.insertPending(
                uuid, name, FAKE_TYPE, amount, FAKE_TYPE, FAKE_TYPE, FAKE_PROVIDER, config().serverId, now,
            )
            logger.debug { "fakecard ref=$referenceCode uuid=$uuid amount=$amount" }
            award(referenceCode, uuid, name, amount, CardOutcome(PaymentStatus.SUCCESS, null, null, "fakecard"))
        }
    }

    /**
     * Apply a gateway outcome to the record. Shared by submit and the poll service:
     * WAITING stores the handle, SUCCESS awards once, FAILED closes the order.
     */
    fun applyOutcome(referenceCode: String, uuid: UUID, name: String?, declaredAmount: Long, outcome: CardOutcome) {
        when (outcome.status) {
            PaymentStatus.WAITING -> {
                cardPaymentDao.markWaiting(referenceCode, outcome.transactionId, System.currentTimeMillis())
                logger.debug { "Card WAITING ref=$referenceCode tx=${outcome.transactionId}" }
            }
            PaymentStatus.SUCCESS -> award(referenceCode, uuid, name, declaredAmount, outcome)
            PaymentStatus.FAILED -> {
                cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
                logger.debug { "Card FAILED ref=$referenceCode: ${outcome.message}" }
                val reason = outcome.message.ifBlank { messages().get(MessageKeys.CARD_REASON_GENERIC) }
                message(uuid, MessageKeys.CARD_FAILED, "reason" to reason)
            }
            PaymentStatus.PENDING -> Unit
        }
    }

    /** Mark an expired WAITING order FAILED (called by the poll service on timeout). */
    fun timeout(referenceCode: String, uuid: UUID) {
        val rows = cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
        if (rows == 1) {
            logger.warn("Card order $referenceCode timed out while WAITING; marked FAILED.")
            message(uuid, MessageKeys.CARD_FAILED, "reason" to messages().get(MessageKeys.CARD_REASON_TIMEOUT))
        }
    }

    /** Idempotent, amount-matched success: reward and accumulate totals exactly once. */
    fun award(referenceCode: String, uuid: UUID, name: String?, declaredAmount: Long, outcome: CardOutcome) {
        val recognized = outcome.recognizedAmount
        if (recognized != null && recognized != declaredAmount) {
            cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
            logger.warn("Card $referenceCode amount mismatch: declared=$declaredAmount recognized=$recognized; not rewarded.")
            message(uuid, MessageKeys.CARD_WRONG_DENOMINATION)
            return
        }

        val point = config().card.denominations[declaredAmount]
        if (point == null) {
            cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
            logger.warn("Card $referenceCode: amount $declaredAmount is not a configured denomination; not rewarded.")
            message(uuid, MessageKeys.CARD_WRONG_DENOMINATION)
            return
        }

        if (!currency.active.isAvailable()) {
            // Reward backend is down: keep the order open instead of flipping it SUCCESS so points
            // are never credited silently and the poll service can settle it once currency returns.
            cardPaymentDao.markWaiting(referenceCode, outcome.transactionId, System.currentTimeMillis())
            logger.error("Card $referenceCode: currency provider unavailable; left WAITING, reward not credited.")
            message(uuid, MessageKeys.CURRENCY_UNAVAILABLE)
            return
        }

        // Flip status and accumulate totals in one transaction so they commit together (exactly once);
        // the external point credit is then applied under a separate gate so a reconcile pass can
        // re-credit a SUCCESS order whose credit never landed (e.g. a crash before grantReward).
        val now = System.currentTimeMillis()
        val committed = try {
            database.transaction { conn ->
                val rows = cardPaymentDao.resolveSuccessWithinTxn(conn, referenceCode, point, now)
                if (rows != 1) throw NotResolvableException()
                playerTotalsDao.add(conn, uuid, METHOD_CARD, declaredAmount, point, now)
            }
            true
        } catch (e: NotResolvableException) {
            logger.debug { "Card $referenceCode already resolved; skipping reward." }
            false
        } catch (e: Exception) {
            logger.error("Card $referenceCode: confirm transaction failed.", e)
            false
        }
        if (!committed) return

        logger.debug { "Card SUCCESS ref=$referenceCode uuid=$uuid +${point}pt amount=$declaredAmount" }
        applyReward(referenceCode, uuid, name, declaredAmount, point)
    }

    /** Re-apply the gated external credit for a SUCCESS order whose credit was not applied (reconcile). */
    fun reapplyReward(order: CardPayment) {
        if (order.status != PaymentStatus.SUCCESS) return
        applyReward(order.referenceCode, order.playerUuid, order.playerName, order.amount, order.point)
    }

    /**
     * Gate the external reward so the resolving caller and any reconcile pass credit at most once:
     * credit points by uuid (the currency provider dispatches the credit to the region thread
     * itself), then enqueue the player-present reward (success message and reward commands) to the
     * outbox so it reaches the player on whichever node they are online and survives a rejoin.
     */
    private fun applyReward(referenceCode: String, uuid: UUID, name: String?, declaredAmount: Long, point: Long) {
        if (cardPaymentDao.claimRewardApplied(referenceCode, System.currentTimeMillis()) != 1) {
            logger.debug { "Card $referenceCode: reward already applied; skipping credit." }
            return
        }
        try {
            currency.active.give(uuid, point)
        } catch (e: Exception) {
            logger.error("Card $referenceCode: reward credit failed uuid=$uuid point=$point; reconcile manually.", e)
        }
        val playerName = name ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
        val vars = mapOf(
            "player" to playerName,
            "amount" to declaredAmount.toString(),
            "point" to point.toString(),
            "ref" to referenceCode,
        )
        val commands = config().rewards.commandsFor(declaredAmount).map { RewardCommands.format(it, vars) }
        rewardSink.enqueue(
            uuid,
            referenceCode,
            RewardPayload(
                messageKey = MessageKeys.CARD_SUCCESS,
                messageVars = mapOf("amount" to Text.formatMoney(declaredAmount), "point" to point.toString()),
                commands = commands,
            ),
        )
    }

    private fun message(uuid: UUID, key: String, vararg vars: Pair<String, String>) {
        val player = Bukkit.getPlayer(uuid) ?: return
        scheduler.runAtEntity(player) { messages().send(player, key, *vars) }
    }

    companion object {
        const val METHOD_CARD = "card"
        private const val MAX_FIELD_LENGTH = 64
        private const val FAKE_TYPE = "FAKE"
        private const val FAKE_PROVIDER = "fake"
    }
}
