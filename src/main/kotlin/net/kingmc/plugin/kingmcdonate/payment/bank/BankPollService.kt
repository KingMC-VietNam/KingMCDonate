package net.kingmc.plugin.kingmcdonate.payment.bank

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.runExclusively
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.render.QrMapRenderer
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives bank confirmation for this node (outbound, no bound port): once on startup then on a
 * recurring timer, with an overlap guard. Two concerns split by scope: **timeout and reconcile stay
 * owner-scoped** (each node fails only its own stale orders and delivers rewards only to its own
 * players, housekeeping always run), while the **gateway match is network-wide** — when [queryGateway]
 * returns true this node acts as the confirmer and matches every node's PENDING (and recently-failed)
 * orders against its incoming transfers in one call, routing confirmations to [BankConfirmService].
 * In webhook-only or passive confirmation [queryGateway] returns false: the gateway is not polled, but
 * timeout, late-transfer reconcile and startup resume still run. It is read each pass so a reloaded
 * confirmation mode / webhook toggle takes effect on the next sweep. A confirmer resolving another
 * node's order only flips it SUCCESS; the owning node delivers the reward (see [BankConfirmService]).
 */
class BankPollService(
    private val bankPaymentDao: BankPaymentDao,
    private val providers: BankProviderRegistry,
    private val confirmService: BankConfirmService,
    private val qrRenderer: QrMapRenderer,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
    private val queryGateway: () -> Boolean = { true },
) {

    private val polling = AtomicBoolean(false)

    /** Called when an order expires and is closed as failed. Notification only. */
    var onFailed: (uuid: UUID, amountVnd: Long, referenceCode: String, reason: String) -> Unit = { _, _, _, _ -> }

    fun start() {
        scheduler.runIo(::poll)
        val periodTicks = config().bank.pollIntervalSeconds.coerceAtLeast(1) * TICKS_PER_SECOND
        scheduler.runTimerAsync({ scheduler.runIo(::poll) }, periodTicks, periodTicks)
    }

    private fun poll() = polling.runExclusively(logger, "bank") {
        try {
            pollOnce()
        } catch (e: Exception) {
            logger.warn("Bank poll pass failed: ${e.message}")
        }
    }

    private fun pollOnce() {
        val serverId = config().serverId
        val now = System.currentTimeMillis()
        val timeoutMillis = config().bank.timeoutMinutes.coerceAtLeast(1) * 60_000L

        // Timeout stays owner-scoped: each node closes only its own stale orders.
        val expired = bankPaymentDao.findPendingByServer(serverId).filter { now - it.createdAt > timeoutMillis }
        for (order in expired) {
            if (bankPaymentDao.markFailed(order.referenceCode, now) == 1) {
                logger.warn("Bank order ${order.referenceCode} timed out; marked FAILED.")
                KingMCDonateContext.activityLogOrNull?.log("TXN", "bank FAILED ref=${order.referenceCode} reason=timeout")
                notifyExpired(order.playerUuid)
                onFailed(order.playerUuid, order.amount, order.referenceCode, "expired")
            }
        }

        if (queryGateway() && providers.isAvailable) {
            // The confirmer matches the whole network's PENDING orders (plus recently-failed ones so a
            // late transfer is surfaced, not dropped) in a single gateway call — own-expired orders were
            // just failed above, so re-reading pending excludes them and the failed-since set catches them.
            val pending = bankPaymentDao.findPendingAllServers()
            val recentFailed = bankPaymentDao.findFailedSinceAllServers(now - timeoutMillis * LATE_WINDOW_FACTOR)
            val orders = pending + recentFailed
            if (orders.isNotEmpty()) {
                logger.debug { "Polling ${pending.size} pending + ${recentFailed.size} recent-failed order(s) network-wide" }
                val result = providers.active.poll(orders)
                result.confirmations.forEach(confirmService::confirm)
                // Only transfers that matched nothing — a wrong-amount payer is surfaced here, never credited.
                result.unmatched.forEach(confirmService::reportUnmatched)
            }
        }

        // Reconcile SUCCESS orders whose external credit did not land (e.g. currency was briefly down).
        bankPaymentDao.findSuccessUnrewardedByServer(serverId).forEach(confirmService::reapplyReward)
    }

    private fun notifyExpired(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        scheduler.runAtEntity(player) {
            messages().send(player, MessageKeys.BANK_EXPIRED)
            qrRenderer.clear(player)
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20L
        private const val LATE_WINDOW_FACTOR = 2L
    }
}
