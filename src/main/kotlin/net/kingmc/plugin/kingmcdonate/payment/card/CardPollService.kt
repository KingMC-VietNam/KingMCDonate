package net.kingmc.plugin.kingmcdonate.payment.card

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.runExclusively
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardRequest
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-polls WAITING card orders owned by this node: once on startup and then on a
 * recurring timer. Each pass re-checks every order against the active gateway and
 * resolves it through [CardPaymentService]; orders older than the configured timeout
 * are failed. Working from the database (not an online-player set) means a restart or
 * a player logging off mid-charge does not lose the resolution.
 */
class CardPollService(
    private val cardPaymentDao: CardPaymentDao,
    private val service: CardPaymentService,
    private val providers: CardProviderRegistry,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
) {

    private val polling = AtomicBoolean(false)

    fun start() {
        scheduler.runIo(::poll)
        val periodTicks = (config().card.pollIntervalSeconds.coerceAtLeast(1)) * TICKS_PER_SECOND
        scheduler.runTimerAsync({ scheduler.runIo(::poll) }, periodTicks, periodTicks)
    }

    private fun poll() = polling.runExclusively(logger, "card") { pollOnce() }

    private fun pollOnce() {
        val serverId = config().serverId
        val waiting = cardPaymentDao.findWaitingByServer(serverId)
        if (waiting.isEmpty()) return

        val provider = providers.active
        val timeoutMillis = config().card.timeoutMinutes.coerceAtLeast(1) * 60_000L
        val now = System.currentTimeMillis()
        logger.debug { "Polling ${waiting.size} WAITING card order(s) on server '$serverId'" }

        for (payment in waiting) {
            try {
                if (now - payment.createdAt > timeoutMillis) {
                    service.timeout(payment.referenceCode, payment.playerUuid)
                    continue
                }
                if (payment.cardProvider != provider.name) {
                    logger.debug { "Skipping ${payment.referenceCode}: provider '${payment.cardProvider}' is not active." }
                    continue
                }
                val type = CardType.parse(payment.cardType) ?: continue
                val request = CardRequest(
                    payment.playerUuid, type, payment.amount, payment.serial, payment.pin, payment.referenceCode,
                )
                val outcome = provider.check(payment.transactionId ?: payment.referenceCode, request)
                service.applyOutcome(payment.referenceCode, payment.playerUuid, payment.playerName, payment.amount, outcome)
            } catch (e: Exception) {
                logger.warn("Failed to poll card order ${payment.referenceCode}: ${e.message}")
            }
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20L
    }
}
