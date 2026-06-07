package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import org.bukkit.command.CommandSender

/**
 * `/kingmcdonate reload` — re-reads config + messages and rebuilds the active
 * currency provider. On a parse failure the previous config is kept (see
 * [ConfigManager.reload]) and a failure message is sent.
 */
class ReloadCommand(
    private val configManager: ConfigManager,
    private val currency: CurrencyRegistry,
) : SubCommand {

    override val name = "reload"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        if (configManager.reload()) {
            currency.load(configManager.config.currency)
            configManager.messages.send(sender, MessageKeys.RELOAD_SUCCESS)
        } else {
            configManager.messages.send(sender, MessageKeys.RELOAD_FAILED, "error" to "xem console")
        }
    }
}
