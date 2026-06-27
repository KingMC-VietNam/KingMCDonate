package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneBossBar
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/napbossbar` — let a player hide/show their milestone bossbar. */
class BossBarCommand(
    private val bossBar: MilestoneBossBar,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            messages().send(sender, MessageKeys.PLAYER_ONLY)
            return true
        }
        if (!config().bossbar.enabled) {
            messages().send(sender, MessageKeys.BOSSBAR_DISABLED)
            return true
        }
        val visible = bossBar.toggle(sender)
        messages().send(sender, if (visible) MessageKeys.BOSSBAR_SHOWN else MessageKeys.BOSSBAR_HIDDEN)
        return true
    }
}
