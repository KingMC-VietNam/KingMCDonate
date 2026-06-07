package net.kingmc.plugin.kingmcdonate

import com.tcoded.folialib.FoliaLib
import net.kingmc.plugin.kingmcdonate.command.CommandRouter
import net.kingmc.plugin.kingmcdonate.command.ReloadCommand
import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.gui.GuiListener
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.plugin.java.JavaPlugin

class KingMCDonate : JavaPlugin() {

    private lateinit var pluginLogger: PluginLogger
    private lateinit var foliaLib: FoliaLib
    private lateinit var scheduler: Scheduler
    private var database: Database? = null

    override fun onEnable() {
        pluginLogger = PluginLogger(logger)
        try {
            bootstrap()
        } catch (e: Exception) {
            pluginLogger.error("Failed to enable KingMCDonate; disabling plugin.", e)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        if (this::scheduler.isInitialized) scheduler.shutdown()
        database?.close()
    }

    /** Wires every subsystem in dependency order. Any failure aborts enable. */
    private fun bootstrap() {
        foliaLib = FoliaLib(this)
        scheduler = Scheduler(foliaLib)
        KingMCDonateContext.init(this, scheduler, pluginLogger)

        val configManager = ConfigManager(this, pluginLogger).apply { load() }
        val config = configManager.config
        if (config.serverId == "default") {
            pluginLogger.warn("server-id is 'default'; set a unique server-id per node before running multi-server.")
        }

        val database = Database(config.database, dataFolder, pluginLogger).apply {
            connect()
            migrate()
        }
        this.database = database

        val currency = CurrencyRegistry(
            pluginLogger,
            CurrencyRegistry.defaultFactory(config.currency, scheduler, pluginLogger),
        ).apply { load(config.currency) }

        KingMCDonateContext.initCore(configManager, database, currency)

        registerCommands(configManager, currency)
        server.pluginManager.registerEvents(GuiListener(), this)

        pluginLogger.info("KingMCDonate enabled (platform: ${platformName()}).")
        pluginLogger.debug { "Bootstrap complete; config + database + currency ready." }
    }

    private fun registerCommands(configManager: ConfigManager, currency: CurrencyRegistry) {
        val router = CommandRouter { configManager.messages }.apply {
            register(ReloadCommand(configManager, currency))
        }
        getCommand("kingmcdonate")?.apply {
            setExecutor(router)
            tabCompleter = router
        }
    }

    private fun platformName(): String = when {
        foliaLib.isFolia -> "Folia"
        foliaLib.isPaper -> "Paper"
        foliaLib.isSpigot -> "Spigot"
        else -> "Unknown"
    }
}
