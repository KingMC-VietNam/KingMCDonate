package net.kingmc.plugin.kingmcdonate.command

import org.bukkit.command.CommandSender

/**
 * A `/kingmcdonate <name> ...` subcommand. [args] excludes the subcommand name.
 * A null [permission] means no permission is required.
 */
interface SubCommand {

    val name: String

    val permission: String?

    fun execute(sender: CommandSender, args: List<String>)

    /** Completions for the next argument; default none. */
    fun tabComplete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
