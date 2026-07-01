package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardCommands
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import java.util.UUID

/** A confirmed, idempotently-resolved donation handed to post-success processing. */
data class Donation(
    val uuid: UUID,
    val name: String?,
    val method: String,
    val amountVnd: Long,
    val point: Long,
    val referenceCode: String,
    val successMessageKey: String,
    val provider: String,
)

/**
 * The single post-success entry point, called once per order inside the caller's
 * `reward_applied` gate. It enqueues the player-present success reward (message +
 * reward commands), runs the one-time first-topup grant, optionally broadcasts, and
 * delegates milestones / Discord / leaderboard / bossbar to injected hooks (so those
 * subsystems plug in without changing this file). Runs on a virtual IO thread.
 */
class DonationSuccessService(
    private val rewardSink: RewardSink,
    private val playerDao: PlayerDao,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val broadcaster: (Donation) -> Unit,
) {

    // Reassignable so the engagement subsystem can attach milestone/Discord/leaderboard/bossbar
    // collaborators after this service is constructed, without rebuilding it.
    var milestoneHook: (Donation) -> Unit = {}
    var discordHook: (Donation) -> Unit = {}
    var leaderboardHook: (Donation) -> Unit = {}
    var bossbarHook: (UUID) -> Unit = {}
    var eventHook: (Donation) -> Unit = {}

    fun onSuccess(d: Donation) {
        val name = resolveName(d)
        logger.debug { "Donation success ref=${d.referenceCode} uuid=${d.uuid} method=${d.method} amount=${d.amountVnd} point=${d.point}" }

        enqueueSuccessReward(d, name)
        runFirstTopup(d, name)
        leaderboardHook(d)
        milestoneHook(d)
        discordHook(d)
        if (config().broadcast.onSuccess) broadcaster(d)
        bossbarHook(d.uuid)
        eventHook(d)
    }

    private fun enqueueSuccessReward(d: Donation, name: String) {
        val vars = vars(d, name)
        val commands = config().rewards.commandsFor(d.amountVnd).map { RewardCommands.format(it, vars) }
        rewardSink.enqueue(
            d.uuid,
            d.referenceCode,
            RewardPayload(
                messageKey = d.successMessageKey,
                messageVars = mapOf("amount" to Text.formatMoney(d.amountVnd), "point" to d.point.toString()),
                commands = commands,
            ),
        )
    }

    private fun runFirstTopup(d: Donation, name: String) {
        val ft = config().firstTopup
        if (!ft.enabled || ft.commands.isEmpty()) return
        if (!playerDao.claimFirstTopup(d.uuid)) return
        val vars = vars(d, name)
        val commands = ft.commands.map { RewardCommands.format(it, vars) }
        logger.debug { "First-topup grant ref=${d.referenceCode} uuid=${d.uuid}" }
        rewardSink.enqueue(d.uuid, d.referenceCode, RewardPayload(commands = commands))
    }

    private fun vars(d: Donation, name: String): Map<String, String> = mapOf(
        "player" to name,
        "amount" to d.amountVnd.toString(),
        "point" to d.point.toString(),
        "ref" to d.referenceCode,
    )

    private fun resolveName(d: Donation): String =
        d.name ?: playerDao.findName(d.uuid) ?: Bukkit.getOfflinePlayer(d.uuid).name ?: d.uuid.toString()
}
