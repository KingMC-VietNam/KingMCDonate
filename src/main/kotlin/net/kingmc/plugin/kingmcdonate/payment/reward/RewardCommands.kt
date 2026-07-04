package net.kingmc.plugin.kingmcdonate.payment.reward

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Runs configured reward commands. Each line may start with a context prefix —
 * `console:` (default) or `player:` (run as the player) — followed by the command
 * body, in which `{player}`/`{amount}`/`{point}`/`{ref}` tokens are substituted.
 * `player:` lines are skipped when the target player is offline.
 *
 * Shared by every donation method so a card and a bank top-up of the same amount run
 * the same rewards. Call from a region/main thread: `console:` lines dispatch inline,
 * while `player:` lines are re-scheduled onto the player's own region thread to stay
 * Folia-safe.
 */
object RewardCommands {

    enum class Context { CONSOLE, PLAYER }

    data class Parsed(val context: Context, val command: String)

    fun run(
        commands: List<String>,
        uuid: UUID,
        playerName: String,
        replacements: Map<String, String>,
        scheduler: Scheduler,
        logger: PluginLogger,
    ) {
        if (commands.isEmpty()) return
        val console = Bukkit.getConsoleSender()
        for (raw in commands) {
            val parsed = parse(raw)
            val command = format(parsed.command, replacements)
            if (command.isBlank()) {
                logger.debug { "Reward command skipped (empty body): $raw" }
                continue
            }
            when (parsed.context) {
                // Console commands are server-global and safe to dispatch on the current region thread.
                Context.CONSOLE -> {
                    KingMCDonateContext.activityLogOrNull?.log("CONSOLE", "reward for $playerName: $command")
                    Bukkit.dispatchCommand(console, command)
                }
                // Player commands touch region-bound player state: run them on the player's own
                // region thread (Folia-safe), and skip when the player is offline.
                Context.PLAYER -> {
                    val player = Bukkit.getPlayer(uuid)
                    if (player == null) {
                        logger.debug { "Reward command skipped (player $playerName offline): $command" }
                        continue
                    }
                    KingMCDonateContext.activityLogOrNull?.log("PLAYER", "reward as $playerName: $command")
                    scheduler.runAtEntity(player) { player.performCommand(command) }
                }
            }
        }
    }

    /** Split an optional context prefix from the command body. */
    fun parse(raw: String): Parsed {
        val trimmed = raw.trimStart()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("console:") -> Parsed(Context.CONSOLE, trimmed.substring("console:".length).trimStart())
            lower.startsWith("player:") -> Parsed(Context.PLAYER, trimmed.substring("player:".length).trimStart())
            else -> Parsed(Context.CONSOLE, trimmed)
        }
    }

    /** Replace every `{token}` from [replacements] in [command]. */
    fun format(command: String, replacements: Map<String, String>): String {
        var result = command
        for ((token, value) in replacements) result = result.replace("{$token}", value)
        return result
    }
}
