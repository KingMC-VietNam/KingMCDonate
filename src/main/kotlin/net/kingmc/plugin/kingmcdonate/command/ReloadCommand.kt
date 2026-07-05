package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.gui.GuiManager
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuRegistry
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneBossBar
import net.kingmc.plugin.kingmcdonate.placeholder.KmdExpansion
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import org.bukkit.command.CommandSender

/**
 * `/kingmcdonate reload` — re-reads config + messages, rebuilds the active currency,
 * card and bank providers, reloads the menu definitions, and invalidates open GUIs so
 * stale menus close instead of mis-routing clicks. Also invalidates the leaderboard cache,
 * stops the bossbar (repaints on next tick with new config), and re-registers the PAPI
 * expansion. It also rebuilds the webhook server via [reloadWebhook] so a changed
 * webhook-secret / host / port / base-path / enabled / confirmation mode applies without a
 * restart. On a parse failure the previous config is kept (see [ConfigManager.reload])
 * and a failure message is sent.
 */
class ReloadCommand(
    private val configManager: ConfigManager,
    private val currency: CurrencyRegistry,
    private val cardProviders: CardProviderRegistry,
    private val bankProviders: BankProviderRegistry,
    private val menuRegistry: MenuRegistry,
    private val guiManager: GuiManager?,
    private val bossBar: MilestoneBossBar?,
    private val leaderboard: LeaderboardService?,
    private val expansion: KmdExpansion?,
    private val reloadWebhook: () -> Unit,
) : SubCommand {

    override val name = "reload"
    override val permission = "kingmcdonate.admin"

    override fun execute(sender: CommandSender, args: List<String>) {
        KingMCDonateContext.activityLogOrNull?.log("COMMAND", "reload by ${sender.name}")
        if (configManager.reload()) {
            currency.load(configManager.config.currency)
            cardProviders.load(configManager.config.card.provider)
            bankProviders.load(configManager.config.bank.provider)
            // Rebuild after providers reload so a new webhook-secret / host / port / enabled / mode applies now.
            reloadWebhook()
            menuRegistry.load()
            guiManager?.invalidate()
            leaderboard?.invalidate(null)
            bossBar?.stop()
            expansion?.installIfPresent()
            configManager.messages.send(sender, MessageKeys.RELOAD_SUCCESS)
        } else {
            configManager.messages.send(sender, MessageKeys.RELOAD_FAILED, "error" to "xem console")
        }
    }
}
