package net.kingmc.plugin.kingmcdonate.payment.card

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.payment.runExclusively
import net.kingmc.plugin.kingmcdonate.provider.card.CardProvider
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardRequest
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-polls the open card orders owned by this node — PENDING as well as WAITING — once on
 * startup and then on a recurring timer.
 *
 * A PENDING order still inside its timeout is skipped entirely: its charge POST may be in
 * flight on this node, and checking it would race that submit into a FAILED order that the
 * landing charge could never be awarded against. Past the timeout no submit can still be
 * running (a charge is bounded by the HTTP timeouts, minutes below `card.timeout`), so the
 * order is either an orphan from a dead node or a stale WAITING — both are reconciled by one
 * final idempotent gateway check and then closed.
 *
 * Each pass fails orders older than the timeout (housekeeping, always
 * run) and, when [queryGateway] returns true, also re-checks each WAITING order against the active
 * gateway and resolves it through [CardPaymentService]. In webhook-only confirmation
 * [queryGateway] returns false: the gateway is not polled, but the timeout sweep and startup
 * resume still run so a missing callback never strands an order. It is read once per pass so a
 * reloaded confirmation mode / webhook toggle takes effect on the next sweep. Working from the
 * database (not an online-player set) means a restart or a logout does not lose the
 * resolution.
 *
 * To stay under gateway rate limits the gateway checks are spaced by `pollSpacingMillis`
 * within a sweep, and an order whose check fails (e.g. the gateway is rate-limiting after
 * the HTTP client's own retries) is skipped with an exponential per-order backoff so a
 * sustained outage is not hammered every sweep.
 */
class CardPollService(
    private val cardPaymentDao: CardPaymentDao,
    private val service: CardPaymentService,
    private val providers: CardProviderRegistry,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val queryGateway: () -> Boolean = { true },
) {

    private val polling = AtomicBoolean(false)

    /** Per-order gateway backoff, keyed by reference code; pruned each sweep to WAITING orders. */
    private val backoff = ConcurrentHashMap<String, BackoffState>()

    private class BackoffState(var failures: Int, var nextAttemptAt: Long)

    fun start() {
        scheduler.runIo(::poll)
        val periodTicks = (config().card.pollIntervalSeconds.coerceAtLeast(1)) * TICKS_PER_SECOND
        scheduler.runTimerAsync({ scheduler.runIo(::poll) }, periodTicks, periodTicks)
    }

    private fun poll() = polling.runExclusively(logger, "card") { pollOnce() }

    internal fun pollOnce() {
        val serverId = config().serverId
        reconcileUnrewarded(serverId)
        val resolvable = cardPaymentDao.findResolvableByServer(serverId)
        if (resolvable.isEmpty()) {
            backoff.clear()
            return
        }
        // Forget backoff for orders that are no longer open (resolved or timed out elsewhere).
        backoff.keys.retainAll(resolvable.mapTo(HashSet()) { it.referenceCode })

        val provider = providers.active
        val timeoutMillis = config().card.timeoutMinutes.coerceAtLeast(1) * 60_000L
        val spacingMillis = config().card.pollSpacingMillis
        val now = System.currentTimeMillis()
        // Read the gateway-query decision once per pass so a reload's mode/toggle change takes effect next sweep.
        val shouldQueryGateway = queryGateway()
        logger.debug {
            "Sweeping ${resolvable.size} open card order(s) on '$serverId' (queryGateway=$shouldQueryGateway)"
        }

        var checkedAny = false
        for (payment in resolvable) {
            try {
                // A PENDING order inside its timeout may have its charge POST in flight on this node
                // right now. Checking it would race that submit: the gateway does not know the card
                // yet, so the check reports failure, the order is closed FAILED, and the charge that
                // lands a moment later can never be awarded. Leave it strictly alone until it ages out.
                if (payment.status == PaymentStatus.PENDING && now - payment.createdAt <= timeoutMillis) continue
                if (now - payment.createdAt > timeoutMillis) {
                    // Force one final check (ignoring backoff) before failing so a charged-but-slow card
                    // that the backoff starved isn't failed unchecked; timeout() no-ops if it resolved.
                    if (shouldQueryGateway && payment.cardProvider == provider.name) forceFinalCheck(payment, provider)
                    service.timeout(payment.referenceCode, payment.playerUuid, payment.amount)
                    continue
                }
                // Webhook-only: housekeeping (timeout above) runs, but the gateway is not polled.
                if (!shouldQueryGateway) continue
                if (payment.cardProvider != provider.name) {
                    logger.debug { "Skipping ${payment.referenceCode}: provider '${payment.cardProvider}' is not active." }
                    continue
                }
                // Skip orders still inside their backoff window after a recent gateway failure.
                if (backoff[payment.referenceCode]?.let { System.currentTimeMillis() < it.nextAttemptAt } == true) {
                    continue
                }
                val type = CardType.parse(payment.cardType) ?: continue
                val request = CardRequest(
                    payment.playerUuid, type, payment.amount, payment.serial, payment.pin, payment.referenceCode,
                )
                if (checkedAny && spacingMillis > 0) sleepQuietly(spacingMillis)
                checkedAny = true
                val outcome = provider.check(payment.transactionId ?: payment.referenceCode, request)
                backoff.remove(payment.referenceCode)
                service.applyOutcome(payment.referenceCode, payment.playerUuid, payment.playerName, payment.amount, outcome)
            } catch (e: Exception) {
                val delay = registerBackoff(payment.referenceCode)
                logger.warn("Failed to poll card order ${payment.referenceCode}: ${e.message}; backing off ${delay}ms")
            }
        }
    }

    /** Final pre-timeout gateway check; resolves the order through the shared path so timeout() then no-ops. */
    private fun forceFinalCheck(payment: CardPayment, provider: CardProvider) {
        val type = CardType.parse(payment.cardType) ?: return
        val request = CardRequest(
            payment.playerUuid, type, payment.amount, payment.serial, payment.pin, payment.referenceCode,
        )
        try {
            val outcome = provider.check(payment.transactionId ?: payment.referenceCode, request)
            service.applyOutcome(payment.referenceCode, payment.playerUuid, payment.playerName, payment.amount, outcome)
        } catch (e: Exception) {
            logger.warn("Final pre-timeout check failed for ${payment.referenceCode}: ${e.message}")
        }
    }

    /** Re-credit SUCCESS orders whose external credit never landed (e.g. a crash before the credit). */
    private fun reconcileUnrewarded(serverId: String) {
        val unrewarded = cardPaymentDao.findSuccessUnrewardedByServer(serverId)
        if (unrewarded.isEmpty()) return
        logger.debug { "Reconciling ${unrewarded.size} SUCCESS card order(s) with unapplied credit on '$serverId'" }
        for (order in unrewarded) {
            try {
                service.reapplyReward(order)
            } catch (e: Exception) {
                logger.error("Failed to reconcile card order ${order.referenceCode}: ${e.message}", e)
            }
        }
    }

    /** Grow this order's backoff window after a failed check and return the applied delay. */
    private fun registerBackoff(referenceCode: String): Long {
        val state = backoff.computeIfAbsent(referenceCode) { BackoffState(0, 0L) }
        state.failures++
        val shift = (state.failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        val delay = (BACKOFF_BASE_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
        state.nextAttemptAt = System.currentTimeMillis() + delay
        return delay
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20L
        private const val BACKOFF_BASE_MILLIS = 30_000L
        private const val MAX_BACKOFF_MILLIS = 600_000L
        private const val MAX_BACKOFF_SHIFT = 5
    }
}
