package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.gui.GuiManager
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuRegistry
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import org.bukkit.command.CommandSender

/**
 * `/kingmcdonate reload` — re-reads config + messages, rebuilds the active currency,
 * card and bank providers, reloads the menu definitions, and invalidates open GUIs so
 * stale menus close instead of mis-routing clicks. On a parse failure the previous
 * config is kept (see [ConfigManager.reload]) and a failure message is sent.
 */
class ReloadCommand(
    private val configManager: ConfigManager,
    private val currency: CurrencyRegistry,
    private val cardProviders: CardProviderRegistry,
    private val bankProviders: BankProviderRegistry,
    private val menuRegistry: MenuRegistry,
    private val guiManager: GuiManager?,
) : SubCommand {

    override val name = "reload"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        if (configManager.reload()) {
            currency.load(configManager.config.currency)
            cardProviders.load(configManager.config.card.provider)
            bankProviders.load(configManager.config.bank.provider)
            menuRegistry.load()
            guiManager?.invalidate()
            configManager.messages.send(sender, MessageKeys.RELOAD_SUCCESS)
        } else {
            configManager.messages.send(sender, MessageKeys.RELOAD_FAILED, "error" to "xem console")
        }
    }
}
