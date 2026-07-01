package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import java.util.UUID

/**
 * Credits an admin-issued top-up: a real balance grant for refunds, compensation or
 * sponsored gifts, tagged `provider = "manual"` so it stays distinguishable from
 * gateway revenue. Points use the flat bank rate with no promo bonus, or an explicit
 * override. The reward converges on [DonationSuccessService] like any other success,
 * so totals, leaderboard, milestones, Discord and the offline reward outbox all apply.
 * The single source of truth for manual credit — shared by the command and the API.
 */
class ManualCreditService(
    private val database: Database,
    private val cardPaymentDao: CardPaymentDao,
    private val bankPaymentDao: BankPaymentDao,
    private val playerTotalsDao: PlayerTotalsDao,
    private val playerDao: PlayerDao,
    private val currency: CurrencyRegistry,
    private val donationSuccess: DonationSuccessService,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
) {

    /** Which totals bucket the manual credit accrues to; also the reward `method`. */
    enum class Bucket(val method: String) { CARD("card"), BANK("bank") }

    private class NotResolvableException : RuntimeException()

    /**
     * Record a SUCCESS manual credit for [uuid] and run the full reward path off-thread.
     * [pointOverride] replaces the default flat-rate points when non-null.
     */
    fun give(bucket: Bucket, uuid: UUID, name: String, amount: Long, pointOverride: Long?) {
        scheduler.runIo {
            val now = System.currentTimeMillis()
            val point = pointOverride ?: Math.round(amount / 1000.0 * config().bank.pointRate)
            playerDao.upsert(uuid, name)
            val referenceCode = when (bucket) {
                Bucket.CARD -> cardPaymentDao.insertPending(
                    uuid, name, MANUAL_TYPE, amount, MANUAL_TYPE, MANUAL_TYPE, MANUAL_PROVIDER, config().serverId, now,
                )
                Bucket.BANK -> bankPaymentDao.insertPending(
                    uuid, amount, MANUAL_PROVIDER, config().serverId, now, config().bank.prefix,
                )
            }
            logger.debug { "Manual credit ref=$referenceCode uuid=$uuid bucket=${bucket.method} amount=$amount point=$point" }

            val committed = try {
                database.transaction { conn ->
                    val rows = when (bucket) {
                        Bucket.CARD -> cardPaymentDao.resolveSuccessWithinTxn(conn, referenceCode, point, now)
                        Bucket.BANK -> bankPaymentDao.resolveSuccessWithinTxn(conn, referenceCode, point, now)
                    }
                    if (rows != 1) throw NotResolvableException()
                    playerTotalsDao.add(conn, uuid, bucket.method, amount, point, now)
                }
                true
            } catch (e: NotResolvableException) {
                logger.warn("Manual credit $referenceCode: could not resolve to SUCCESS; skipped.")
                false
            } catch (e: Exception) {
                logger.error("Manual credit $referenceCode: confirm transaction failed.", e)
                false
            }
            if (!committed) return@runIo

            applyReward(bucket, referenceCode, uuid, name, amount, point)
        }
    }

    private fun applyReward(bucket: Bucket, referenceCode: String, uuid: UUID, name: String, amount: Long, point: Long) {
        val claimed = when (bucket) {
            Bucket.CARD -> cardPaymentDao.claimRewardApplied(referenceCode, System.currentTimeMillis())
            Bucket.BANK -> bankPaymentDao.claimRewardApplied(referenceCode, System.currentTimeMillis())
        }
        if (claimed != 1) {
            logger.debug { "Manual credit $referenceCode: reward already applied; skipping credit." }
            return
        }
        try {
            currency.active.give(uuid, point)
        } catch (e: Exception) {
            logger.error("Manual credit $referenceCode: reward credit failed uuid=$uuid point=$point; reconcile manually.", e)
        }
        donationSuccess.onSuccess(
            Donation(uuid, name, bucket.method, amount, point, referenceCode, MessageKeys.MANUAL_SUCCESS),
        )
    }

    companion object {
        const val MANUAL_PROVIDER = "manual"
        private const val MANUAL_TYPE = "MANUAL"
    }
}
