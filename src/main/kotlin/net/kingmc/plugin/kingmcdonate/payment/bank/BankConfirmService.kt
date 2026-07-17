package net.kingmc.plugin.kingmcdonate.payment.bank

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.database.dao.isUniqueViolation
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardGate
import net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation
import net.kingmc.plugin.kingmcdonate.provider.bank.UnmatchedTransfer
import net.kingmc.plugin.kingmcdonate.promo.PromoService
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.sql.SQLException
import java.util.UUID

/**
 * The single convergence point for every bank confirmation. It loads the order by
 * reference regardless of status; a PENDING order is resolved by one transaction
 * that records the gateway transaction, flips the status and accumulates totals
 * (all exactly-once), after which the external point credit is applied under a
 * conditional `reward_applied` gate (at most once). Amount mismatches are not
 * rewarded; transfers for an already-failed order are recorded and surfaced once.
 */
class BankConfirmService(
    private val database: Database,
    private val bankPaymentDao: BankPaymentDao,
    private val processedBankTxDao: ProcessedBankTxDao,
    private val playerTotalsDao: PlayerTotalsDao,
    private val playerDao: PlayerDao,
    private val currency: CurrencyRegistry,
    private val promo: PromoService,
    private val donationSuccess: DonationSuccessService,
    private val clearQr: (UUID) -> Unit,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
) {

    private class NotPendingException : RuntimeException()

    private val rewardGate = RewardGate(currency, donationSuccess, logger)

    /** Route a confirmed transfer: branch on the order's current status. */
    fun confirm(confirmation: BankConfirmation) {
        val order = bankPaymentDao.findByReference(confirmation.referenceCode)
        if (order == null) {
            logger.debug { "Confirm: no order for ref=${confirmation.referenceCode}; skipping." }
            return
        }
        when (order.status) {
            PaymentStatus.PENDING -> resolvePending(order, confirmation)
            PaymentStatus.FAILED -> lateTransfer(order, confirmation)
            PaymentStatus.SUCCESS -> extraTransfer(order, confirmation)
            PaymentStatus.WAITING -> logger.debug { "Confirm: ref=${order.referenceCode} WAITING (unused for bank)." }
        }
    }

    /**
     * Re-apply the gated external credit for a SUCCESS order whose credit was not applied (reconcile).
     * This is also the delivery path when a *different* node (the confirmer) flipped the order: the
     * owning node credits the reward and clears the payer's QR here, which [resolvePending] deferred.
     */
    fun reapplyReward(order: BankPayment) {
        if (order.status != PaymentStatus.SUCCESS) return
        applyReward(order, order.point)
        clearQr(order.playerUuid)
    }

    private fun resolvePending(order: BankPayment, confirmation: BankConfirmation) {
        // Unreachable in practice — both ingress paths find the order *by* amount, so a confirmation
        // always carries the order's amount, and a real wrong-amount transfer never gets this far
        // (it is surfaced by reportUnmatched instead). Kept as a last guard on the credit path: no
        // future caller should be able to build a mismatched confirmation and have it credited.
        if (confirmation.amount != order.amount) {
            logger.error(
                "Bank ${order.referenceCode}: confirmation amount ${confirmation.amount} does not match the " +
                    "order's ${order.amount}; refusing to credit. This should be unreachable — please report it.",
            )
            return
        }
        if (!currency.active.isAvailable()) {
            logger.warn("Bank ${order.referenceCode}: currency provider unavailable; left PENDING for a later pass.")
            return
        }

        val point = pointFor(order.amount)
        val now = System.currentTimeMillis()
        val committed = try {
            database.transaction { conn ->
                processedBankTxDao.insertWithinTxn(conn, confirmation.transactionId, order.referenceCode, now)
                val rows = bankPaymentDao.resolveSuccessWithinTxn(conn, order.referenceCode, point, now)
                if (rows != 1) throw NotPendingException()
                playerTotalsDao.add(conn, order.playerUuid, METHOD_BANK, order.amount, point, now)
            }
            true
        } catch (e: NotPendingException) {
            logger.debug { "Bank ${order.referenceCode} no longer PENDING at flip; skipping." }
            false
        } catch (e: SQLException) {
            if (isUniqueViolation(e)) {
                logger.debug { "Bank tx ${confirmation.transactionId} already processed; skipping." }
            } else {
                logger.error("Bank ${order.referenceCode}: confirm transaction failed.", e)
            }
            false
        }
        if (!committed) return

        logger.debug { "Bank SUCCESS ref=${order.referenceCode} uuid=${order.playerUuid} amount=${order.amount} +${point}pt" }
        // The financial flip above is exactly-once network-wide, but the external reward + QR clear are
        // node-local: only the owning node can credit/clear for a player connected to it. When a confirmer
        // resolved another node's order, leave reward_applied = 0 so the owner delivers it on reconcile.
        if (order.ownerServer == config().serverId) {
            applyReward(order, point)
            clearQr(order.playerUuid)
        } else {
            logger.debug { "Bank ${order.referenceCode} confirmed cross-server; reward deferred to owner '${order.ownerServer}'." }
        }
    }

    /** Gate the external reward so confirm and any reconcile pass together apply it at most once. */
    private fun applyReward(order: BankPayment, point: Long) {
        rewardGate.applyOnce(
            "Bank ${order.referenceCode}", order.playerUuid, point,
            claim = { bankPaymentDao.claimRewardApplied(order.referenceCode, System.currentTimeMillis()) },
        ) {
            val name = playerDao.findName(order.playerUuid)
            Donation(order.playerUuid, name, METHOD_BANK, order.amount, point, order.referenceCode, MessageKeys.BANK_SUCCESS, order.provider)
        }
    }

    /**
     * Surface an incoming transfer that matched no order but names a PENDING one **at a different
     * amount** — the payer got the figure wrong, so today it is dropped and neither they nor an
     * operator ever learns why. Warns once per transfer and never credits: only the exact-amount
     * match may do that.
     *
     * Safe only because [u] matched **nothing** ([UnmatchedTransfer]), and because the marker is
     * keyed via [ProcessedBankTxDao.mismatchKey] rather than the bare tx id. A transfer's text can
     * name several orders — one stale, one correct — and recording the bare id here would make the
     * correct one's confirmation violate UNIQUE and roll back, losing that credit for good.
     */
    fun reportUnmatched(u: UnmatchedTransfer) {
        // Diagnostics only: it must never break the caller. The webhook handlers promise their gateway
        // a 200 for any authentic transfer, and a 500 here would make the gateway retry — and, for a
        // batch payload, abandon the real confirmations queued behind this one.
        try {
            val named = bankPaymentDao.findPendingByContainedReferenceAnyAmount(u.searchText) ?: return
            // Same amount means the order simply wasn't in the match set (the poll caps how many
            // orders it compares). The next pass credits it; saying "NOT credited" here would invite
            // an operator to pay by hand and double-credit the player.
            if (named.amount == u.amount) {
                logger.debug { "Bank ${named.referenceCode}: tx=${u.transactionId} pays the right amount; leaving it to the next pass." }
                return
            }
            val first = processedBankTxDao.insertIfAbsent(
                ProcessedBankTxDao.mismatchKey(u.transactionId), named.referenceCode, System.currentTimeMillis(),
            )
            if (first) {
                logger.warn(
                    "Bank ${named.referenceCode}: transfer (tx=${u.transactionId}) names this order but paid " +
                        "${u.amount} instead of ${named.amount}; NOT credited, recorded for manual reconciliation.",
                )
                KingMCDonateContext.activityLogOrNull?.log(
                    "TXN",
                    "bank amount-mismatch ref=${named.referenceCode} tx=${u.transactionId} " +
                        "paid=${u.amount} expected=${named.amount}",
                )
            }
        } catch (e: Exception) {
            logger.error("Could not report unmatched transfer tx=${u.transactionId}; ignored.", e)
        }
    }

    private fun lateTransfer(order: BankPayment, confirmation: BankConfirmation) {
        val first = processedBankTxDao.insertIfAbsent(
            confirmation.transactionId, order.referenceCode, System.currentTimeMillis(),
        )
        if (first) {
            logger.warn(
                "Bank ${order.referenceCode}: transfer (tx=${confirmation.transactionId}, ${confirmation.amount}) " +
                    "arrived after the order FAILED; recorded for manual reconciliation.",
            )
        }
    }

    /**
     * A confirmation for an already-SUCCESS order. The original transfer's tx id was recorded inside
     * the confirm transaction, so a replay of the same tx is a no-op; a *new* tx id means a second
     * real transfer for the same reference (player double-paid) — record and surface it once.
     */
    private fun extraTransfer(order: BankPayment, confirmation: BankConfirmation) {
        val first = processedBankTxDao.insertIfAbsent(
            confirmation.transactionId, order.referenceCode, System.currentTimeMillis(),
        )
        if (first) {
            logger.warn(
                "Bank ${order.referenceCode}: extra transfer (tx=${confirmation.transactionId}, ${confirmation.amount}) " +
                    "for an already-SUCCESS order; recorded for manual reconciliation.",
            )
        } else {
            logger.debug { "Confirm: ref=${order.referenceCode} already SUCCESS, tx already recorded; skipping." }
        }
    }

    /** Base points (amount/1000 * rate) with the active promo bonus applied. */
    fun pointFor(amountVnd: Long): Long {
        val base = Math.round(amountVnd / 1000.0 * config().bank.pointRate)
        return promo.applyBonus(base, System.currentTimeMillis())
    }

    companion object {
        const val METHOD_BANK = "bank"
    }
}
