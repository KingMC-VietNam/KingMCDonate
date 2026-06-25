package net.kingmc.plugin.kingmcdonate.payment.bank

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
 * Polls this node's PENDING bank orders (outbound, no bound port): once on startup
 * then on a recurring timer, with an overlap guard. Each pass fails orders past the
 * timeout and reconciles any SUCCESS order whose external credit has not been applied
 * (housekeeping, always run). When [queryGateway] is set it also asks the gateway to
 * match the live (and recently-failed) orders against its incoming transfers and routes
 * confirmations to [BankConfirmService]. In webhook-only confirmation [queryGateway] is
 * false: the gateway is not polled, but timeout, late-transfer reconcile and startup
 * resume still run. Owner-server scoping keeps two nodes from polling each other's orders.
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
    private val queryGateway: Boolean = true,
) {

    private val polling = AtomicBoolean(false)

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

        val pending = bankPaymentDao.findPendingByServer(serverId)
        val (expired, live) = pending.partition { now - it.createdAt > timeoutMillis }
        for (order in expired) {
            if (bankPaymentDao.markFailed(order.referenceCode, now) == 1) {
                logger.warn("Bank order ${order.referenceCode} timed out; marked FAILED.")
                notifyExpired(order.playerUuid)
            }
        }

        if (queryGateway && providers.isAvailable) {
            // Match against live orders plus recently-failed ones so a late transfer is surfaced, not dropped.
            val recentFailed = bankPaymentDao.findFailedByServerSince(serverId, now - timeoutMillis * LATE_WINDOW_FACTOR)
            val orders = live + recentFailed
            if (orders.isNotEmpty()) {
                logger.debug { "Polling ${live.size} live + ${recentFailed.size} recent-failed order(s) on '$serverId'" }
                providers.active.poll(orders).forEach(confirmService::confirm)
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
