package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * Dispatches `/kingmcdonate <sub> ...` to registered [SubCommand]s and provides
 * permission-filtered tab completion. [messages] is a supplier so it always reads
 * the current (post-reload) message holder.
 */
class CommandRouter(private val messages: () -> Messages) : CommandExecutor, TabCompleter {

    private val subCommands = LinkedHashMap<String, SubCommand>()

    fun register(subCommand: SubCommand) {
        subCommands[subCommand.name.lowercase()] = subCommand
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            messages().send(sender, MessageKeys.UNKNOWN_COMMAND)
            return true
        }
        val sub = subCommands[args[0].lowercase()]
        if (sub == null) {
            messages().send(sender, MessageKeys.UNKNOWN_COMMAND)
            return true
        }
        if (!hasPermission(sender, sub)) {
            messages().send(sender, MessageKeys.NO_PERMISSION)
            return true
        }
        sub.execute(sender, args.drop(1))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size <= 1) {
            val prefix = args.firstOrNull().orEmpty()
            return subCommands.values
                .filter { hasPermission(sender, it) }
                .map { it.name }
                .filter { it.startsWith(prefix, ignoreCase = true) }
        }
        val sub = subCommands[args[0].lowercase()] ?: return emptyList()
        if (!hasPermission(sender, sub)) return emptyList()
        return sub.tabComplete(sender, args.drop(1))
    }

    private fun hasPermission(sender: CommandSender, sub: SubCommand): Boolean =
        sub.permission == null || sender.hasPermission(sub.permission!!)
}
