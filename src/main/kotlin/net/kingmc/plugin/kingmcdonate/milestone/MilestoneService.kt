package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardCommands
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Periods
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import java.util.UUID

/**
 * Checks donation milestones after a successful top-up. For each period the player's
 * (or the whole server's) new total is compared against configured thresholds;
 * every uncompleted threshold at or below the total is granted retroactively, each
 * exactly once via a UNIQUE completion claim. Both player and server rewards are
 * delivered through the outbox [RewardSink] so they benefit from the same retry
 * and delivery guarantees as regular donation rewards.
 */
class MilestoneService(
    private val dao: MilestoneDao,
    private val playerMilestones: () -> MilestoneConfig,
    private val serverMilestones: () -> MilestoneConfig,
    private val rewardSink: RewardSink,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
) {

    /**
     * How server-milestone commands are run. Server rewards are global (not tied to the
     * triggering donor's presence), so they run on the confirming node next tick. Injectable
     * so tests can capture without a live server.
     */
    var serverRewardRunner: (List<String>, Donation) -> Unit = { commands, d ->
        scheduler.runNextTick {
            RewardCommands.run(commands, d.uuid, d.name ?: d.uuid.toString(), emptyMap(), scheduler, logger)
        }
    }

    /** Called after a player milestone is granted when a triggering donation is present. */
    var onPlayerMilestone: (Donation, Long) -> Unit = { _, _ -> }

    /** Called after a server milestone is granted. */
    var onServerMilestone: (Donation, Long) -> Unit = { _, _ -> }

    /** Full check after a success: player milestones for the donor, server milestones globally. */
    fun check(d: Donation) {
        val now = System.currentTimeMillis()
        val name = d.name ?: d.uuid.toString()
        checkPlayerInternal(d.uuid, name, d, now)
        checkServer(d, now)
    }

    /** Player-only check (used on join / resume); no triggering donation context. */
    fun checkPlayer(uuid: UUID, name: String) {
        checkPlayerInternal(uuid, name, null, System.currentTimeMillis())
    }

    private fun checkPlayerInternal(uuid: UUID, name: String, d: Donation?, now: Long) {
        val cfg = playerMilestones()
        for (period in Period.entries) {
            val entries = cfg.forPeriod(period)
            if (entries.isEmpty()) continue
            val periodKey = Periods.key(period, now)
            val current = dao.playerTotal(uuid, period, periodKey)
            val done = dao.completedThresholds(MilestoneDao.SCOPE_PLAYER, uuid.toString(), period.name, periodKey)
            for (entry in entries) {
                if (entry.threshold > current || entry.threshold in done) continue
                if (!dao.claimCompletion(MilestoneDao.SCOPE_PLAYER, uuid.toString(), period.name, periodKey, entry.threshold, now)) continue
                logger.debug { "Player milestone reached uuid=$uuid period=$period threshold=${entry.threshold}" }
                grantPlayer(uuid, name, entry, current, d)
            }
        }
    }

    private fun checkServer(d: Donation, now: Long) {
        val cfg = serverMilestones()
        for (period in Period.entries) {
            val entries = cfg.forPeriod(period)
            if (entries.isEmpty()) continue
            val periodKey = Periods.key(period, now)
            val current = dao.serverTotal(period, periodKey)
            val done = dao.completedThresholds(MilestoneDao.SCOPE_SERVER, MilestoneDao.SERVER_SUBJECT, period.name, periodKey)
            for (entry in entries) {
                if (entry.threshold > current || entry.threshold in done) continue
                if (!dao.claimCompletion(MilestoneDao.SCOPE_SERVER, MilestoneDao.SERVER_SUBJECT, period.name, periodKey, entry.threshold, now)) continue
                logger.warn("Server milestone reached: ${entry.threshold} (${period.name}), triggered by ${d.name}")
                grantServer(d, entry, current)
            }
        }
    }

    private fun grantPlayer(uuid: UUID, name: String, entry: MilestoneConfig.Entry, current: Long, d: Donation?) {
        val vars = vars(name, entry, current, d)
        val commands = entry.commands.map { RewardCommands.format(it, vars) }
        val message = entry.message?.let { applyVars(it, vars) }
        rewardSink.enqueue(uuid, d?.referenceCode ?: "milestone", RewardPayload(message = message, commands = commands))
        if (d != null) onPlayerMilestone(d, entry.threshold)
    }

    private fun grantServer(d: Donation, entry: MilestoneConfig.Entry, current: Long) {
        val vars = vars(d.name ?: d.uuid.toString(), entry, current, d)
        val commands = entry.commands.map { RewardCommands.format(it, vars) }
        // Server reward commands are global (not tied to the triggering donor's presence):
        // run on the confirming node via the injectable runner.
        serverRewardRunner(commands, d)
        onServerMilestone(d, entry.threshold)
    }

    private fun vars(name: String, entry: MilestoneConfig.Entry, current: Long, d: Donation?): Map<String, String> = mapOf(
        "player" to name,
        "threshold" to entry.threshold.toString(),
        "current" to current.toString(),
        "amount" to (d?.amountVnd?.toString() ?: "0"),
        "point" to (d?.point?.toString() ?: "0"),
        "ref" to (d?.referenceCode ?: ""),
    )

    private fun applyVars(raw: String, vars: Map<String, String>): String =
        vars.entries.fold(raw) { acc, (k, v) -> acc.replace("{$k}", v) }
}
