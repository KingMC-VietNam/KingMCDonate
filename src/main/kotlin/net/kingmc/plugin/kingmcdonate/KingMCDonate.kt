package net.kingmc.plugin.kingmcdonate

import com.tcoded.folialib.FoliaLib
import net.kingmc.plugin.kingmcdonate.command.BankCommand
import net.kingmc.plugin.kingmcdonate.command.CommandRouter
import net.kingmc.plugin.kingmcdonate.command.FakeBankSubCommand
import net.kingmc.plugin.kingmcdonate.command.FakeCardSubCommand
import net.kingmc.plugin.kingmcdonate.command.LichSuNapCommand
import net.kingmc.plugin.kingmcdonate.command.LichSuSubCommand
import net.kingmc.plugin.kingmcdonate.command.NapTheCommand
import net.kingmc.plugin.kingmcdonate.command.ReloadCommand
import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PendingRewardDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.gui.CardInput
import net.kingmc.plugin.kingmcdonate.gui.CardTopupMenu
import net.kingmc.plugin.kingmcdonate.gui.ChatInputListener
import net.kingmc.plugin.kingmcdonate.gui.GuiListener
import net.kingmc.plugin.kingmcdonate.gui.HistoryMenu
import net.kingmc.plugin.kingmcdonate.payment.BankConfirmService
import net.kingmc.plugin.kingmcdonate.payment.BankPaymentService
import net.kingmc.plugin.kingmcdonate.payment.BankPollService
import net.kingmc.plugin.kingmcdonate.payment.CardPaymentService
import net.kingmc.plugin.kingmcdonate.payment.CardPollService
import net.kingmc.plugin.kingmcdonate.payment.RewardDeliveryListener
import net.kingmc.plugin.kingmcdonate.payment.RewardDeliveryService
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.render.PacketEventsQrMapRenderer
import net.kingmc.plugin.kingmcdonate.render.QrListener
import net.kingmc.plugin.kingmcdonate.render.QrMapRenderer
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit
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

        val http = Http(
            config.http.connectTimeoutSeconds,
            config.http.requestTimeoutSeconds,
            config.http.maxRetries,
            pluginLogger,
        )
        val rewardDelivery = RewardDeliveryService(
            PendingRewardDao(database), scheduler, pluginLogger,
            { configManager.config }, { configManager.messages },
        )

        val card = setupCard(http, configManager, database, currency, rewardDelivery)
        val bank = setupBank(http, configManager, database, currency, rewardDelivery)

        registerCommands(configManager, currency, card, bank)
        server.pluginManager.registerEvents(GuiListener(), this)
        server.pluginManager.registerEvents(card.chatInput, this)
        server.pluginManager.registerEvents(RewardDeliveryListener(rewardDelivery), this)
        server.pluginManager.registerEvents(QrListener(bank.qrRenderer, scheduler), this)

        rewardDelivery.start()
        card.pollService.start()
        bank.pollService.start()

        pluginLogger.info("KingMCDonate enabled (platform: ${platformName()}).")
        pluginLogger.debug { "Bootstrap complete; config + database + currency + card + bank ready." }
    }

    /** Builds the card top-up subsystem: provider registry, DAOs, services and UI. */
    private fun setupCard(
        http: Http,
        configManager: ConfigManager,
        database: Database,
        currency: CurrencyRegistry,
        rewardDelivery: RewardDeliveryService,
    ): CardSubsystem {
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
            rewardDelivery,
            scheduler,
            pluginLogger,
            { configManager.config },
            { configManager.messages },
        )
        val chatInput = ChatInputListener(scheduler) { configManager.messages }
        val menu = CardTopupMenu(service, providers, CardInput(this, chatInput)) { configManager.config }
        val pollService = CardPollService(cardPaymentDao, service, providers, scheduler, pluginLogger) {
            configManager.config
        }
        return CardSubsystem(providers, cardPaymentDao, service, chatInput, menu, pollService)
    }

    private fun enabledCardTypes(configManager: ConfigManager): Set<CardType> =
        configManager.config.card.cardTypes.mapNotNull { CardType.parse(it) }.toSet()

    /** Builds the bank QR subsystem: provider registry, DAOs, renderer, confirm/poll services. */
    private fun setupBank(
        http: Http,
        configManager: ConfigManager,
        database: Database,
        currency: CurrencyRegistry,
        rewardDelivery: RewardDeliveryService,
    ): BankSubsystem {
        val providers = BankProviderRegistry(
            pluginLogger,
            BankProviderRegistry.defaultFactory(http, dataFolder, pluginLogger),
        ).apply { load(configManager.config.bank.provider) }

        val bankPaymentDao = BankPaymentDao(database)
        val qrRenderer: QrMapRenderer = PacketEventsQrMapRenderer(pluginLogger)

        val confirmService = BankConfirmService(
            database,
            bankPaymentDao,
            ProcessedBankTxDao(database),
            PlayerTotalsDao(database),
            PlayerDao(database),
            currency,
            rewardDelivery,
            clearQr = { uuid -> Bukkit.getPlayer(uuid)?.let { p -> scheduler.runAtEntity(p) { qrRenderer.clear(p) } } },
            logger = pluginLogger,
            config = { configManager.config },
        )
        val service = BankPaymentService(
            bankPaymentDao, PlayerDao(database), providers, currency, confirmService, qrRenderer, http,
            scheduler, pluginLogger, { configManager.config }, { configManager.messages },
        )
        val pollService = BankPollService(
            bankPaymentDao, providers, confirmService, qrRenderer, scheduler, pluginLogger,
            { configManager.config }, { configManager.messages },
        )
        return BankSubsystem(providers, bankPaymentDao, service, pollService, qrRenderer)
    }

    private fun registerCommands(
        configManager: ConfigManager,
        currency: CurrencyRegistry,
        card: CardSubsystem,
        bank: BankSubsystem,
    ) {
        val router = CommandRouter { configManager.messages }.apply {
            register(ReloadCommand(configManager, currency, card.providers, bank.providers))
            register(LichSuSubCommand(card.cardPaymentDao, scheduler) { configManager.messages })
            register(FakeCardSubCommand(card.service, { configManager.config }) { configManager.messages })
            register(FakeBankSubCommand(bank.service) { configManager.messages })
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

        val historyMenu = HistoryMenu(card.cardPaymentDao, bank.bankPaymentDao, scheduler)
        val lichSu = LichSuNapCommand(card.cardPaymentDao, historyMenu, scheduler) { configManager.messages }
        getCommand("lichsunap")?.setExecutor(lichSu)

        getCommand("bank")?.setExecutor(BankCommand(bank.service) { configManager.messages })
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
        val pollService: CardPollService,
    )

    /** Bundle of bank-subsystem components shared between bootstrap and command registration. */
    private class BankSubsystem(
        val providers: BankProviderRegistry,
        val bankPaymentDao: BankPaymentDao,
        val service: BankPaymentService,
        val pollService: BankPollService,
        val qrRenderer: QrMapRenderer,
    )
}
