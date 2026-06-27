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
 * online — via a recurring timer and on join. A row is taken by an atomic claim
 * before it is run; the claim wins for exactly one node, and `delivered` is set right
 * after dispatch. A reaper requeues rows a dead claimer never delivered, so delivery
 * is at-least-once and reward commands are expected to tolerate replay.
 */
class RewardDeliveryService(
    private val dao: PendingRewardDao,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) : RewardSink {

    /** Enqueue a player-present reward. The payload is fully resolved at this point. */
    override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) {
        dao.enqueue(playerUuid, referenceCode, gson.toJson(payload), System.currentTimeMillis())
        logger.debug { "Outbox enqueue ref=$referenceCode uuid=$playerUuid" }
    }

    fun start() {
        val period = config().rewardDeliveryIntervalTicks.coerceAtLeast(1)
        scheduler.runTimerAsync({ scheduler.runIo(::drainAndReap) }, period, period)
    }

    /** Deliver any outstanding rewards for a player who just joined this node. */
    fun onJoin(player: Player) {
        scheduler.runIo { deliver(dao.findClaimableFor(listOf(player.uniqueId))) }
    }

    private fun drainAndReap() {
        deliver(dao.findClaimable(BATCH))
        val staleMillis = config().staleClaimMinutes.coerceAtLeast(1) * 60_000L
        val requeued = dao.reapStale(staleMillis, System.currentTimeMillis())
        if (requeued > 0) logger.warn("Requeued $requeued stale outbox reward(s) for re-delivery.")
    }

    private fun deliver(rows: List<PendingReward>) {
        if (rows.isEmpty()) return
        val node = config().serverId
        val now = System.currentTimeMillis()
        for (row in rows) {
            val player = Bukkit.getPlayer(row.playerUuid) ?: continue
            // Claim first: only the single winner runs the payload.
            if (dao.claim(row.id, node, now) != 1) continue
            val payload = parse(row.payload) ?: run {
                dao.markDelivered(row.id)
                continue
            }
            scheduler.runAtEntity(player) {
                runPayload(player, payload)
                scheduler.runIo { dao.markDelivered(row.id) }
            }
            logger.debug { "Outbox delivered ref=${row.referenceCode} uuid=${row.playerUuid} on '$node'" }
        }
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
        private const val BATCH = 100
        private val gson = Gson()
    }
}
