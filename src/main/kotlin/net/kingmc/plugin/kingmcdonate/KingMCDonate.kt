package net.kingmc.plugin.kingmcdonate

import com.tcoded.folialib.FoliaLib
import net.kingmc.plugin.kingmcdonate.command.CommandRouter
import net.kingmc.plugin.kingmcdonate.command.FakeCardSubCommand
import net.kingmc.plugin.kingmcdonate.command.LichSuNapCommand
import net.kingmc.plugin.kingmcdonate.command.LichSuSubCommand
import net.kingmc.plugin.kingmcdonate.command.NapTheCommand
import net.kingmc.plugin.kingmcdonate.command.ReloadCommand
import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.gui.CardInput
import net.kingmc.plugin.kingmcdonate.gui.CardTopupMenu
import net.kingmc.plugin.kingmcdonate.gui.ChatInputListener
import net.kingmc.plugin.kingmcdonate.gui.GuiListener
import net.kingmc.plugin.kingmcdonate.gui.HistoryMenu
import net.kingmc.plugin.kingmcdonate.payment.CardPaymentService
import net.kingmc.plugin.kingmcdonate.payment.CardPollService
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.Http
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

        val card = setupCard(configManager, database, currency)
        registerCommands(configManager, currency, card)
        server.pluginManager.registerEvents(GuiListener(), this)
        server.pluginManager.registerEvents(card.chatInput, this)
        card.pollService.start()

        pluginLogger.info("KingMCDonate enabled (platform: ${platformName()}).")
        pluginLogger.debug { "Bootstrap complete; config + database + currency + card ready." }
    }

    /** Builds the card top-up subsystem: HTTP, provider registry, DAOs, services and UI. */
    private fun setupCard(configManager: ConfigManager, database: Database, currency: CurrencyRegistry): CardSubsystem {
        val http = Http(
            configManager.config.http.connectTimeoutSeconds,
            configManager.config.http.requestTimeoutSeconds,
            configManager.config.http.maxRetries,
            pluginLogger,
        )
        val providers = CardProviderRegistry(
            pluginLogger,
            CardProviderRegistry.defaultFactory({ enabledCardTypes(configManager) }, http, dataFolder, pluginLogger),
        ).apply { load(configManager.config.card.provider) }

        val cardPaymentDao = CardPaymentDao(database)
        val service = CardPaymentService(
            cardPaymentDao,
            PlayerTotalsDao(database),
            PlayerDao(database),
            currency,
            providers,
            scheduler,
            pluginLogger,
            { configManager.config },
            { configManager.messages },
        )
        val chatInput = ChatInputListener(scheduler) { configManager.messages }
        val menu = CardTopupMenu(service, providers, CardInput(this, chatInput)) { configManager.config }
        val historyMenu = HistoryMenu(cardPaymentDao, scheduler)
        val pollService = CardPollService(cardPaymentDao, service, providers, scheduler, pluginLogger) {
            configManager.config
        }
        return CardSubsystem(providers, cardPaymentDao, service, chatInput, menu, historyMenu, pollService)
    }

    private fun enabledCardTypes(configManager: ConfigManager): Set<CardType> =
        configManager.config.card.cardTypes.mapNotNull { CardType.parse(it) }.toSet()

    private fun registerCommands(configManager: ConfigManager, currency: CurrencyRegistry, card: CardSubsystem) {
        val router = CommandRouter { configManager.messages }.apply {
            register(ReloadCommand(configManager, currency, card.providers))
            register(LichSuSubCommand(card.cardPaymentDao, scheduler) { configManager.messages })
            register(FakeCardSubCommand(card.service, { configManager.config }) { configManager.messages })
        }
        getCommand("kingmcdonate")?.apply {
            setExecutor(router)
            tabCompleter = router
        }

        val napThe = NapTheCommand(card.service, card.menu, { configManager.config }) { configManager.messages }
        getCommand("napthe")?.apply {
            setExecutor(napThe)
            tabCompleter = napThe
        }

        val lichSu = LichSuNapCommand(card.cardPaymentDao, card.historyMenu, scheduler) { configManager.messages }
        getCommand("lichsunap")?.setExecutor(lichSu)
    }

    private fun platformName(): String = when {
        foliaLib.isFolia -> "Folia"
        foliaLib.isPaper -> "Paper"
        foliaLib.isSpigot -> "Spigot"
        else -> "Unknown"
    }

    /** Bundle of card-subsystem components shared between bootstrap and command registration. */
    private class CardSubsystem(
        val providers: CardProviderRegistry,
        val cardPaymentDao: CardPaymentDao,
        val service: CardPaymentService,
        val chatInput: ChatInputListener,
        val menu: CardTopupMenu,
        val historyMenu: HistoryMenu,
        val pollService: CardPollService,
    )
}
