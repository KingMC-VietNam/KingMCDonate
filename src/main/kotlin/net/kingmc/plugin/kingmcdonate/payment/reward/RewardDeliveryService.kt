package net.kingmc.plugin.kingmcdonate.payment.reward

import com.google.gson.Gson
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.util.Text
import net.kingmc.plugin.kingmcdonate.database.dao.PendingRewardDao
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The multi-node reward outbox. Player-present rewards (messages, reward commands)
 * are enqueued by the confirming node and delivered by whichever node has the player
 * online — via a recurring timer and on join.
 *
 * Delivery is **at-most-once**: [PendingRewardDao.claimAndDeliver] claims a row and marks
 * it delivered in one statement, before the payload runs, so exactly one node dispatches
 * a row and nothing can hand it out again. A crash between that mark and the dispatch
 * loses that single reward — deliberate, because the alternative replays reward commands
 * (`give`, `lp add`) and silently inflates the economy. Losses are logged by reference for
 * manual reconciliation. The financial point credit is separate (`RewardGate`) and unaffected.
 */
class RewardDeliveryService(
    private val dao: PendingRewardDao,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
    // Seam: is the player online on THIS node right now? Injected so delivery is testable.
    private val onlineHere: (UUID) -> Boolean = { Bukkit.getPlayer(it) != null },
) : RewardSink {

    /** Enqueue a player-present reward. The payload is fully resolved at this point. */
    override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {
        dao.enqueue(playerUuid, referenceCode, gson.toJson(payload), System.currentTimeMillis())
        logger.debug { "Outbox enqueue ref=$referenceCode uuid=$playerUuid" }
    }

    fun start() {
        val period = config().rewardDeliveryIntervalTicks.coerceAtLeast(1)
        scheduler.runTimerAsync({ scheduler.runIo(::drain) }, period, period)
        scheduler.runTimerAsync({ scheduler.runIo(::purge) }, PURGE_PERIOD_TICKS, PURGE_PERIOD_TICKS)
    }

    /** Drop delivered rows past the configured retention so the outbox does not grow without bound. */
    private fun purge() {
        val retentionDays = config().rewardOutboxRetentionDays
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
        val removed = dao.purgeDelivered(cutoff)
        if (removed > 0) logger.info("Outbox purge removed $removed delivered rows older than $retentionDays day(s).")
    }

    /** Deliver any outstanding rewards for a player who just joined this node. */
    fun onJoin(player: Player) {
        scheduler.runIo { deliver(dao.findClaimableFor(listOf(player.uniqueId))) }
    }

    private fun drain() {
        deliver(dao.findClaimable(BATCH))
    }

    private fun deliver(rows: List<PendingReward>) {
        if (rows.isEmpty()) return
        val node = config().serverId
        for ((row, payload) in claimDeliverable(rows, node, System.currentTimeMillis())) {
            logger.info("Outbox dispatching ref=${row.referenceCode} uuid=${row.playerUuid} on '$node'.")
            // Re-resolve: the claim above already marked the row, so if the player left in between
            // this reward is gone. Log it by reference — that is the at-most-once trade.
            val player = Bukkit.getPlayer(row.playerUuid) ?: run {
                logger.error(
                    "Outbox reward ref=${row.referenceCode} uuid=${row.playerUuid} was claimed on '$node' but the " +
                        "player left before dispatch; it is LOST and needs manual reconciliation.",
                )
                continue
            }
            scheduler.runAtEntity(player) { runPayload(player, payload) }
        }
    }

    /**
     * The rows this node may run: the player is online here and the atomic claim-and-mark won.
     * Marking happens here, before any dispatch, which is what makes delivery at-most-once.
     * Returns data only (no `Player`), so the invariant is testable without a running server.
     */
    internal fun claimDeliverable(
        rows: List<PendingReward>,
        node: String,
        now: Long,
    ): List<Pair<PendingReward, RewardPayload>> = rows.mapNotNull { row ->
        if (!onlineHere(row.playerUuid)) return@mapNotNull null
        if (dao.claimAndDeliver(row.id, node, now) != 1) return@mapNotNull null
        // An unreadable payload is dropped: the row is already marked, so it is not retried forever.
        parse(row.payload)?.let { row to it }
    }

    private fun runPayload(player: Player, payload: RewardPayload) {
        payload.messageKey?.let { key ->
            messages().send(player, key, *payload.messageVars.map { it.key to it.value }.toTypedArray())
        }
        payload.message?.let { player.sendMessage(Text.colorize(it)) }
        if (payload.commands.isNotEmpty()) {
            RewardCommands.run(payload.commands, player.uniqueId, player.name, emptyMap(), scheduler, logger)
        }
    }

    private fun parse(json: String): RewardPayload? = try {
        gson.fromJson(json, RewardPayload::class.java)
    } catch (e: Exception) {
        logger.error("Could not parse outbox payload; dropping: $json", e)
        null
    }

    companion object {
        private const val BATCH = 200
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        // Hourly: retention is measured in days, so the outbox never grows more than an hour past cutoff.
        private const val PURGE_PERIOD_TICKS = 20L * 60 * 60
        private val gson = Gson()
    }
}
