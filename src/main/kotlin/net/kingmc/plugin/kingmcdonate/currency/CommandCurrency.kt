package net.kingmc.plugin.kingmcdonate.currency

import net.kingmc.plugin.kingmcdonate.payment.reward.RewardCommands
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Runs configured commands as the reward. Always available. Each line may start with a
 * `console:` (default) or `player:` prefix — same syntax as reward/milestone commands —
 * so the credit can be granted either from console or as the player. Commands are
 * dispatched on the global/main region via [Scheduler.runNextTick] because Bukkit
 * command dispatch is not thread-safe and may be called from an async success path.
 * `{player}` and `{amount}` are substituted in each command.
 */
class CommandCurrency(
    val commands: List<String>,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
) : CurrencyProvider {

    override val name = "command"

    override fun isAvailable() = true

    override fun give(uuid: UUID, amount: Long) {
        val playerName = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
        val replacements = mapOf("player" to playerName, "amount" to amount.toString())
        scheduler.runNextTick {
            RewardCommands.run(commands, uuid, playerName, replacements, scheduler, logger, source = "currency")
        }
    }

    /** The Command provider does not expose a readable balance. */
    override fun balance(uuid: UUID): Long = 0
}
