package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.payment.ManualCreditService
import net.kingmc.plugin.kingmcdonate.payment.ManualCreditService.Bucket
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.util.UUID

/**
 * `/kingmcdonate give <card|bank> <player> <amount> [point] [-f]` — credit a real,
 * admin-issued top-up (refund/compensation/gift) to a player, online or offline, for
 * any amount. Points default to the flat bank rate unless an explicit `[point]` is
 * given. Offline targets require `-f`; a name never seen on this server is rejected.
 * The player is resolved off the main thread so an unknown-name lookup can't stall it.
 */
class GiveSubCommand(
    private val service: ManualCreditService,
    private val playerDao: PlayerDao,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) : SubCommand {

    override val name = "give"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        when (val parsed = parse(args)) {
            GiveParse.Usage -> messages().send(sender, MessageKeys.GIVE_USAGE)
            GiveParse.InvalidBucket -> messages().send(sender, MessageKeys.GIVE_INVALID_BUCKET)
            is GiveParse.Ok -> scheduler.runIo { resolveAndGive(sender, parsed) }
        }
    }

    /** Resolve the target off-thread: KMD-known players first, else fall back to a (possibly blocking) offline lookup. */
    private fun resolveAndGive(sender: CommandSender, a: GiveParse.Ok) {
        val known = playerDao.findUuid(a.name)
        if (known != null) {
            val online = Bukkit.getPlayer(known)
            finish(sender, a, known, online?.name ?: playerDao.findName(known) ?: a.name, online != null)
            return
        }
        val target = Bukkit.getOfflinePlayer(a.name)
        if (target.name == null || (!target.isOnline && !target.hasPlayedBefore())) {
            feedback(sender, MessageKeys.GIVE_NEVER_JOINED, "player" to a.name)
            return
        }
        finish(sender, a, target.uniqueId, target.name ?: a.name, target.isOnline)
    }

    private fun finish(sender: CommandSender, a: GiveParse.Ok, uuid: UUID, name: String, online: Boolean) {
        if (!online && !a.force) {
            feedback(sender, MessageKeys.GIVE_OFFLINE_NEEDS_FORCE, "player" to name)
            return
        }
        service.give(a.bucket, uuid, name, a.amount, a.point, sender.name)
        logger.info(
            "[MANUAL] actor=${sender.name} target=$name/$uuid method=${a.bucket.method} " +
                "amount=${a.amount} point=${a.point ?: "auto"}",
        )
        KingMCDonateContext.activityLogOrNull?.log(
            "COMMAND",
            "give by ${sender.name}: target=$name method=${a.bucket.method} amount=${a.amount} point=${a.point ?: "auto"}",
        )
        feedback(
            sender, MessageKeys.GIVE_DONE,
            "player" to name, "amount" to Text.formatMoney(a.amount),
        )
    }

    private fun feedback(sender: CommandSender, key: String, vararg vars: Pair<String, String>) {
        scheduler.runNextTick { messages().send(sender, key, *vars) }
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> = when {
        args.size == 1 -> listOf("card", "bank").filter { it.startsWith(args[0], ignoreCase = true) }
        args.size == 2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        args.size == 3 -> config().card.denominations.keys.map { it.toString() }.filter { it.startsWith(args[2]) }
        args.isNotEmpty() && args.last().startsWith("-") -> listOf("-f").filter { it.startsWith(args.last()) }
        else -> emptyList()
    }

    /** Outcome of parsing the positional arguments plus the optional `-f` switch. */
    sealed interface GiveParse {
        data class Ok(val bucket: Bucket, val name: String, val amount: Long, val point: Long?, val force: Boolean) : GiveParse
        object Usage : GiveParse
        object InvalidBucket : GiveParse
    }

    companion object {
        /** Pure parse: `<card|bank> <player> <amount> [point]` with an optional `-f` anywhere. */
        fun parse(args: List<String>): GiveParse {
            val force = args.any { it == "-f" }
            val positional = args.filter { it != "-f" }
            if (positional.size < 3 || positional.size > 4) return GiveParse.Usage
            val bucket = when (positional[0].lowercase()) {
                "card" -> Bucket.CARD
                "bank" -> Bucket.BANK
                else -> return GiveParse.InvalidBucket
            }
            val amount = positional[2].toLongOrNull()
            if (amount == null || amount <= 0) return GiveParse.Usage
            val point = if (positional.size == 4) {
                positional[3].toLongOrNull()?.takeIf { it >= 0 } ?: return GiveParse.Usage
            } else {
                null
            }
            return GiveParse.Ok(bucket, positional[1], amount, point, force)
        }
    }
}
