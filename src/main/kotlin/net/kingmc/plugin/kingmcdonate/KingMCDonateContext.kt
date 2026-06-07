package net.kingmc.plugin.kingmcdonate

import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler

/**
 * Process-wide registry of shared components, populated in [KingMCDonate.onEnable].
 * [init] sets the bootstrap components; [initCore] sets config/DB/currency once they
 * are ready. [config] and [messages] read through the config manager so they always
 * reflect the latest reload.
 */
object KingMCDonateContext {

    lateinit var plugin: KingMCDonate
        private set

    lateinit var scheduler: Scheduler
        private set

    lateinit var logger: PluginLogger
        private set

    lateinit var configManager: ConfigManager
        private set

    lateinit var database: Database
        private set

    lateinit var currency: CurrencyRegistry
        private set

    val config: PluginConfig get() = configManager.config
    val messages: Messages get() = configManager.messages

    internal fun init(plugin: KingMCDonate, scheduler: Scheduler, logger: PluginLogger) {
        this.plugin = plugin
        this.scheduler = scheduler
        this.logger = logger
    }

    internal fun initCore(configManager: ConfigManager, database: Database, currency: CurrencyRegistry) {
        this.configManager = configManager
        this.database = database
        this.currency = currency
    }
}
