package net.kingmc.plugin.kingmcdonate.currency

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Runs configured console commands as the reward. Always available. Commands are
 * dispatched on the global/main region via [Scheduler.runNextTick] because Bukkit
 * command dispatch is not thread-safe and may be called from an async success path.
 * `{player}` and `{amount}` are substituted in each command.
 */
class CommandCurrency(
    private val commands: List<String>,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
) : CurrencyProvider {

    override val name = "command"

    override fun isAvailable() = true

    override fun give(uuid: UUID, amount: Long) {
        val playerName = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
        scheduler.runNextTick {
            for (template in commands) {
                val command = template
                    .replace("{player}", playerName)
                    .replace("{amount}", amount.toString())
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                logger.debug { "Command currency executed for $uuid: $command" }
            }
        }
    }

    /** The Command provider does not expose a readable balance. */
    override fun balance(uuid: UUID): Long = 0
}
