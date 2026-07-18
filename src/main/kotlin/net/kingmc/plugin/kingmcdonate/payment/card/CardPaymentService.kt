package net.kingmc.plugin.kingmcdonate.payment.card

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardGate
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.provider.card.CardOutcome
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardRequest
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.promo.PromoService
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
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
    private val promo: PromoService,
    private val donationSuccess: DonationSuccessService,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
    private val rewardSink: RewardSink,
    // Seam: is the player online on THIS node right now? Injected so the durable-notice branch is testable.
    private val onlineHere: (UUID) -> Boolean = { Bukkit.getPlayer(it) != null },
) {

    private class NotResolvableException : RuntimeException()

    private val rewardGate = RewardGate(currency, donationSuccess, logger)

    /** Called when an order is closed as failed (gateway rejection or timeout). Notification only. */
    var onFailed: (uuid: UUID, amountVnd: Long, referenceCode: String, reason: String) -> Unit = { _, _, _, _ -> }

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
        spamRejection(player.uniqueId, System.currentTimeMillis())?.let { messages().send(player, it); return }

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
        KingMCDonateContext.activityLogOrNull?.log(
            "TXN", "card created ref=$referenceCode player=$name type=${type.name} amount=$declaredAmount provider=${provider.name}",
        )

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

    /**
     * The message key rejecting a new order for [uuid] at [now], or null if the player may proceed —
     * the anti-spam guard, run before any PENDING insert so command, GUI and Bedrock paths throttle
     * alike. Card and bank each check their own table, so the cap and cooldown are naturally per method.
     */
    internal fun spamRejection(uuid: UUID, now: Long): String? {
        val antiSpam = config().antiSpam
        if (antiSpam.maxInFlight > 0 && cardPaymentDao.countOpenByPlayer(uuid) >= antiSpam.maxInFlight) {
            return MessageKeys.ORDER_IN_PROGRESS
        }
        if (antiSpam.cooldownSeconds > 0) {
            val last = cardPaymentDao.latestCreatedAtByPlayer(uuid)
            if (last != null && now - last < antiSpam.cooldownSeconds * 1000) return MessageKeys.ORDER_COOLDOWN
        }
        return null
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
                logFailed(referenceCode, if (outcome.wrongDenomination) "wrong-denomination" else outcome.message.ifBlank { "failed" })
                if (outcome.wrongDenomination) {
                    notifyFailure(referenceCode, uuid, MessageKeys.CARD_WRONG_DENOMINATION)
                } else {
                    val reason = outcome.message.ifBlank { messages().get(MessageKeys.CARD_REASON_GENERIC) }
                    notifyFailure(referenceCode, uuid, MessageKeys.CARD_FAILED, "reason" to reason)
                }
                onFailed(uuid, declaredAmount, referenceCode, outcome.message.ifBlank { "failed" })
            }
            PaymentStatus.PENDING -> Unit
        }
    }

    /** Mark an expired open order FAILED (called by the poll service on timeout). */
    fun timeout(referenceCode: String, uuid: UUID, amountVnd: Long) {
        val rows = cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
        if (rows == 1) {
            logger.warn("Card order $referenceCode timed out unresolved; marked FAILED.")
            logFailed(referenceCode, "timeout")
            notifyFailure(referenceCode, uuid, MessageKeys.CARD_FAILED, "reason" to messages().get(MessageKeys.CARD_REASON_TIMEOUT))
            onFailed(uuid, amountVnd, referenceCode, "timeout")
        }
    }

    /** Idempotent, amount-matched success: reward and accumulate totals exactly once. */
    fun award(referenceCode: String, uuid: UUID, name: String?, declaredAmount: Long, outcome: CardOutcome) {
        val recognized = outcome.recognizedAmount
        if (recognized != null && recognized != declaredAmount) {
            cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
            logger.warn("Card $referenceCode amount mismatch: declared=$declaredAmount recognized=$recognized; not rewarded.")
            logFailed(referenceCode, "amount-mismatch declared=$declaredAmount recognized=$recognized")
            notifyFailure(referenceCode, uuid, MessageKeys.CARD_WRONG_DENOMINATION)
            onFailed(uuid, declaredAmount, referenceCode, "amount-mismatch")
            return
        }

        val basePoint = config().card.denominations[declaredAmount]
        if (basePoint == null) {
            cardPaymentDao.resolve(referenceCode, PaymentStatus.FAILED, 0, System.currentTimeMillis())
            logger.warn("Card $referenceCode: amount $declaredAmount is not a configured denomination; not rewarded.")
            logFailed(referenceCode, "unconfigured-denomination amount=$declaredAmount")
            notifyFailure(referenceCode, uuid, MessageKeys.CARD_WRONG_DENOMINATION)
            onFailed(uuid, declaredAmount, referenceCode, "unconfigured-denomination")
            return
        }

        // Promo is computed from when the player committed (order creation), not confirm time, so a
        // delayed confirmation past the promo's end still grants the bonus the player was shown.
        val createdAt = cardPaymentDao.findByReference(referenceCode)?.createdAt ?: System.currentTimeMillis()
        val point = promo.applyBonus(basePoint, createdAt)

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
        if (!currency.active.isAvailable()) {
            // The charge is real and now banked (status + totals), but `reward_applied` stays 0 so the
            // owner-scoped reconcile pass credits it once the backend returns. Leaving the order open
            // instead would strand it: in webhook-only mode nothing ever re-checks a WAITING order, so
            // it would simply time out FAILED with the card already charged.
            logger.error("Card $referenceCode: currency provider unavailable; SUCCESS recorded, credit deferred to reconcile.")
            message(uuid, MessageKeys.CURRENCY_UNAVAILABLE)
            return
        }
        applyReward(referenceCode, uuid, name, declaredAmount, point, providers.active.name)
    }

    /** Re-apply the gated external credit for a SUCCESS order whose credit was not applied (reconcile). */
    fun reapplyReward(order: CardPayment) {
        if (order.status != PaymentStatus.SUCCESS) return
        applyReward(order.referenceCode, order.playerUuid, order.playerName, order.amount, order.point, order.cardProvider)
    }

    /**
     * Gate the external reward so the resolving caller and any reconcile pass credit at most once:
     * credit points by uuid, then hand off to [DonationSuccessService] for all post-success work
     * (success message + reward commands via the outbox, milestones, first-topup, broadcast, Discord).
     */
    private fun applyReward(referenceCode: String, uuid: UUID, name: String?, declaredAmount: Long, point: Long, provider: String) {
        rewardGate.applyOnce(
            "Card $referenceCode", uuid, point,
            claim = { cardPaymentDao.claimRewardApplied(referenceCode, System.currentTimeMillis()) },
        ) {
            Donation(uuid, name, METHOD_CARD, declaredAmount, point, referenceCode, MessageKeys.CARD_SUCCESS, provider)
        }
    }

    private fun message(uuid: UUID, key: String, vararg vars: Pair<String, String>) {
        if (!onlineHere(uuid)) return
        val player = Bukkit.getPlayer(uuid) ?: return
        scheduler.runAtEntity(player) { messages().send(player, key, *vars) }
    }

    /**
     * Notify the player of a failed card durably: an immediate toast when they are online on this node,
     * otherwise a message-only entry in the reward outbox so it reaches them on rejoin / on whatever node
     * they are online (a card can fail asynchronously — via poll/timeout — after the player has left).
     */
    private fun notifyFailure(referenceCode: String, uuid: UUID, key: String, vararg vars: Pair<String, String>) {
        // Resolve the player once. Null means offline here *or* disconnected between the online check and
        // now — either way the toast would be lost, so it goes to the outbox instead of being dropped.
        val player = if (onlineHere(uuid)) Bukkit.getPlayer(uuid) else null
        if (player != null) {
            scheduler.runAtEntity(player) { messages().send(player, key, *vars) }
        } else {
            rewardSink.enqueue(uuid, referenceCode, RewardPayload(messageKey = key, messageVars = vars.toMap()))
        }
    }

    private fun logFailed(referenceCode: String, reason: String) =
        KingMCDonateContext.activityLogOrNull?.log("TXN", "card FAILED ref=$referenceCode reason=$reason")

    companion object {
        const val METHOD_CARD = "card"
        private const val MAX_FIELD_LENGTH = 64
        private const val FAKE_TYPE = "FAKE"
        private const val FAKE_PROVIDER = "fake"
    }
}
