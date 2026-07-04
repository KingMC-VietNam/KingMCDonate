package net.kingmc.plugin.kingmcdonate

import com.tcoded.folialib.FoliaLib
import net.kingmc.plugin.kingmcdonate.command.BankCommand
import net.kingmc.plugin.kingmcdonate.command.BossBarCommand
import net.kingmc.plugin.kingmcdonate.command.CommandRouter
import net.kingmc.plugin.kingmcdonate.command.FakeBankSubCommand
import net.kingmc.plugin.kingmcdonate.command.GiveSubCommand
import net.kingmc.plugin.kingmcdonate.command.FakeCardSubCommand
import net.kingmc.plugin.kingmcdonate.command.LichSuNapCommand
import net.kingmc.plugin.kingmcdonate.command.LichSuSubCommand
import net.kingmc.plugin.kingmcdonate.command.NapTheCommand
import net.kingmc.plugin.kingmcdonate.command.ReloadCommand
import net.kingmc.plugin.kingmcdonate.command.TopNapCommand
import net.kingmc.plugin.kingmcdonate.api.KingMCDonateAPI
import net.kingmc.plugin.kingmcdonate.api.KingMCDonateAPIImpl
import net.kingmc.plugin.kingmcdonate.api.KmdEventPublisher
import net.kingmc.plugin.kingmcdonate.discord.DiscordService
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneBossBar
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneJoinListener
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneService
import net.kingmc.plugin.kingmcdonate.placeholder.KmdExpansion
import net.kingmc.plugin.kingmcdonate.config.ConfigManager
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.database.dao.MilestoneDao
import net.kingmc.plugin.kingmcdonate.database.dao.PendingRewardDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.database.dao.PointLogDao
import net.kingmc.plugin.kingmcdonate.database.dao.ProcessedBankTxDao
import net.kingmc.plugin.kingmcdonate.gui.screen.CardInput
import net.kingmc.plugin.kingmcdonate.gui.screen.CardTopupMenu
import net.kingmc.plugin.kingmcdonate.gui.screen.ChatInputListener
import net.kingmc.plugin.kingmcdonate.gui.GuiManager
import net.kingmc.plugin.kingmcdonate.gui.screen.HistoryMenu
import net.kingmc.plugin.kingmcdonate.gui.screen.LeaderboardMenu
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuRegistry
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuService
import net.kingmc.plugin.kingmcdonate.hook.PlaceholderApiHook
import net.kingmc.plugin.kingmcdonate.bedrock.BedrockForms
import net.kingmc.plugin.kingmcdonate.bedrock.CardForm
import net.kingmc.plugin.kingmcdonate.bedrock.HistoryForm
import net.kingmc.plugin.kingmcdonate.bedrock.LeaderboardForm
import net.kingmc.plugin.kingmcdonate.leaderboard.HeadResolver
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.payment.ManualCreditService
import net.kingmc.plugin.kingmcdonate.payment.model.PointLogEntry
import net.kingmc.plugin.kingmcdonate.payment.SuccessBroadcaster
import net.kingmc.plugin.kingmcdonate.payment.bank.BankConfirmService
import net.kingmc.plugin.kingmcdonate.payment.bank.BankPaymentService
import net.kingmc.plugin.kingmcdonate.payment.bank.BankPollService
import net.kingmc.plugin.kingmcdonate.payment.card.CardPaymentService
import net.kingmc.plugin.kingmcdonate.payment.card.CardPollService
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardDeliveryListener
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardDeliveryService
import net.kingmc.plugin.kingmcdonate.promo.PromoService
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.render.PacketEventsQrMapRenderer
import net.kingmc.plugin.kingmcdonate.render.QrListener
import net.kingmc.plugin.kingmcdonate.render.QrMapRenderer
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.util.ActivityLog
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookCapable
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.CardWebhookCapable
import net.kingmc.plugin.kingmcdonate.webhook.CardWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookHandler
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRouter
import net.kingmc.plugin.kingmcdonate.webhook.WebhookServer
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class KingMCDonate : JavaPlugin() {

    private lateinit var pluginLogger: PluginLogger
    private lateinit var foliaLib: FoliaLib
    private lateinit var scheduler: Scheduler
    private lateinit var configManager: ConfigManager
    private lateinit var donationSuccess: DonationSuccessService
    private var database: Database? = null
    private var menuRegistry: MenuRegistry? = null
    private var guiManager: GuiManager? = null
    private var webhookServer: WebhookServer? = null
    private var expansion: KmdExpansion? = null
    private var bossBar: MilestoneBossBar? = null
    private var leaderboard: LeaderboardService? = null
    private var activityLog: ActivityLog? = null

    // Re-read on every access so services always see the latest reloaded config/messages.
    private val configRef = { configManager.config }
    private val messagesRef = { configManager.messages }

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
        // Withdraw the public API before tearing anything down so no caller gets a half-dead instance.
        KingMCDonateAPI.set(null)
        server.servicesManager.unregisterAll(this)
        // Stop accepting callbacks before tearing down the scheduler/database the handlers use.
        webhookServer?.stop()
        expansion?.unregisterIfPresent()
        bossBar?.stop()
        leaderboard?.shutdown()
        if (this::scheduler.isInitialized) scheduler.shutdown()
        database?.close()
        activityLog?.close()
    }

    /** Wires every subsystem in dependency order. Any failure aborts enable. */
    private fun bootstrap() {
        foliaLib = FoliaLib(this)
        scheduler = Scheduler(foliaLib)
        KingMCDonateContext.init(this, scheduler, pluginLogger)

        configManager = ConfigManager(this, pluginLogger).apply { load() }
        val config = configManager.config
        if (config.serverId == "default") {
            pluginLogger.warn("server-id is 'default'; set a unique server-id per node before running multi-server.")
        }

        val activityLog = ActivityLog.create(
            dataFolder,
            config.activityLog.enabled,
            config.activityLog.maxSizeKb * 1024,
            config.activityLog.maxFiles,
            pluginLogger,
        )
        this.activityLog = activityLog
        KingMCDonateContext.initActivityLog(activityLog)

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
            PendingRewardDao(database), scheduler, pluginLogger, configRef, messagesRef,
        )

        val promo = PromoService { configManager.promo }
        val broadcaster = SuccessBroadcaster(scheduler, configRef)
        // Hooks default to no-ops; setupEngagement attaches the real ones once milestone/discord/leaderboard exist.
        donationSuccess = DonationSuccessService(
            rewardSink = rewardDelivery,
            playerDao = PlayerDao(database),
            logger = pluginLogger,
            config = configRef,
            broadcaster = broadcaster::broadcast,
        )

        PlaceholderApiHook.install(pluginLogger)
        val guiManager = GuiManager(scheduler)
        val menuRegistry = MenuRegistry(this, pluginLogger).apply { load() }
        val menus = MenuService(menuRegistry, guiManager, pluginLogger)
        this.menuRegistry = menuRegistry
        this.guiManager = guiManager

        // Bedrock forms are optional: only touch the Floodgate-backed code when the plugin is present,
        // so a server without Floodgate never loads org.geysermc classes (no NoClassDefFoundError).
        val bedrockForms: BedrockForms? = if (server.pluginManager.getPlugin("floodgate") != null) {
            BedrockForms.create().also { if (it.isAvailable) pluginLogger.info("Floodgate detected; Bedrock forms enabled.") }
        } else {
            null
        }

        val card = setupCard(http, database, currency, menus, promo, donationSuccess, bedrockForms)
        val bank = setupBank(http, database, currency, promo, donationSuccess)
        startWebhookServer(config, listOfNotNull(card.webhookHandler, bank.webhookHandler))

        // Fire the public Bukkit events off the existing hooks (attach, do not replace them).
        val eventPublisher = KmdEventPublisher(scheduler, pluginLogger)
        card.service.onFailed = { uuid, amount, ref, reason -> eventPublisher.fireFailed(uuid, "card", amount, ref, reason) }
        bank.pollService.onFailed = { uuid, amount, ref, reason -> eventPublisher.fireFailed(uuid, "bank", amount, ref, reason) }
        val manualCredit = ManualCreditService(
            database, card.cardPaymentDao, bank.bankPaymentDao, PlayerTotalsDao(database), PlayerDao(database),
            currency, donationSuccess, scheduler, pluginLogger, configRef,
        )

        setupEngagement(http, database, rewardDelivery, promo, donationSuccess, card.cardPaymentDao, eventPublisher, manualCredit, menus, bedrockForms)
        registerCommands(currency, card, bank, menus, database, manualCredit, bedrockForms)
        server.pluginManager.registerEvents(guiManager, this)
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
        database: Database,
        currency: CurrencyRegistry,
        menus: MenuService,
        promo: PromoService,
        donationSuccess: DonationSuccessService,
        bedrockForms: BedrockForms?,
    ): CardSubsystem {
        val providers = CardProviderRegistry(
            pluginLogger,
            CardProviderRegistry.defaultFactory(::enabledCardTypes, http, dataFolder, pluginLogger),
        ).apply { load(configRef().card.provider) }

        val cardPaymentDao = CardPaymentDao(database)
        val service = CardPaymentService(
            database,
            cardPaymentDao,
            PlayerTotalsDao(database),
            PlayerDao(database),
            currency,
            providers,
            promo,
            donationSuccess,
            scheduler,
            pluginLogger,
            configRef,
            messagesRef,
        )
        val chatInput = ChatInputListener(scheduler, messagesRef)
        val cardInput = CardInput(this, chatInput, messagesRef)
        val cardForm = bedrockForms?.let {
            CardForm(service, providers, it, scheduler, configRef) { configManager.bedrockForms }
        }
        val menu = CardTopupMenu(service, providers, cardInput, menus, configRef, cardForm)

        val mode = configRef().card.confirmation
        val webhookHandler: WebhookHandler? = if (mode.usesWebhook && configRef().webhook.enabled) {
            val active = providers.active
            if (active is CardWebhookCapable) {
                active.webhookHandler(CardWebhookDeps(cardPaymentDao::findByReference, service::applyOutcome, pluginLogger))
            } else {
                pluginLogger.warn(
                    "card.confirmation='${mode.name.lowercase()}' but provider '${active.name}' has no webhook " +
                        "support; falling back to polling for card.",
                )
                null
            }
        } else {
            null
        }
        // Webhook resolves WAITING orders; fall back to gateway polling when no handler is active.
        val queryGateway = mode.pollsGateway || webhookHandler == null
        val pollService =
            CardPollService(cardPaymentDao, service, providers, scheduler, pluginLogger, configRef, queryGateway)
        pluginLogger.debug { "Card confirmation: mode=${mode.name.lowercase()} queryGateway=$queryGateway webhook=${webhookHandler != null}" }
        return CardSubsystem(providers, cardPaymentDao, service, chatInput, menu, pollService, webhookHandler)
    }

    private fun enabledCardTypes(): Set<CardType> =
        configRef().card.cardTypes.mapNotNull { CardType.parse(it) }.toSet()

    /** Builds the bank QR subsystem: provider registry, DAOs, renderer, confirm/poll services. */
    private fun setupBank(
        http: Http,
        database: Database,
        currency: CurrencyRegistry,
        promo: PromoService,
        donationSuccess: DonationSuccessService,
    ): BankSubsystem {
        val providers = BankProviderRegistry(
            pluginLogger,
            BankProviderRegistry.defaultFactory(http, dataFolder, pluginLogger),
        ).apply { load(configRef().bank.provider) }

        val bankPaymentDao = BankPaymentDao(database)
        val qrRenderer: QrMapRenderer = PacketEventsQrMapRenderer(pluginLogger)

        val confirmService = BankConfirmService(
            database,
            bankPaymentDao,
            ProcessedBankTxDao(database),
            PlayerTotalsDao(database),
            PlayerDao(database),
            currency,
            promo,
            donationSuccess,
            clearQr = { uuid -> Bukkit.getPlayer(uuid)?.let { p -> scheduler.runAtEntity(p) { qrRenderer.clear(p) } } },
            logger = pluginLogger,
            config = configRef,
        )
        val service = BankPaymentService(
            bankPaymentDao, PlayerDao(database), providers, currency, confirmService, qrRenderer, http,
            scheduler, pluginLogger, configRef, messagesRef,
        )
        val mode = configRef().bank.confirmation
        val webhookHandler: WebhookHandler? = if (mode.usesWebhook && configRef().webhook.enabled) {
            val active = providers.active
            if (active is BankWebhookCapable) {
                active.webhookHandler(BankWebhookDeps(bankPaymentDao::findByReference, confirmService::confirm, pluginLogger))
            } else {
                pluginLogger.warn(
                    "bank.confirmation='${mode.name.lowercase()}' but provider '${active.name}' has no webhook " +
                        "support; falling back to polling for bank.",
                )
                null
            }
        } else {
            null
        }
        val queryGateway = mode.pollsGateway || webhookHandler == null
        val pollService = BankPollService(
            bankPaymentDao, providers, confirmService, qrRenderer, scheduler, pluginLogger, configRef, messagesRef,
            queryGateway,
        )
        pluginLogger.debug { "Bank confirmation: mode=${mode.name.lowercase()} queryGateway=$queryGateway webhook=${webhookHandler != null}" }
        return BankSubsystem(providers, bankPaymentDao, service, pollService, qrRenderer, webhookHandler)
    }

    /** Start the single shared webhook server when any subsystem registered a handler. */
    private fun startWebhookServer(config: PluginConfig, handlers: List<WebhookHandler>) {
        if (handlers.isEmpty()) return
        val router = WebhookRouter(config.webhook.basePath, handlers, pluginLogger)
        try {
            webhookServer = WebhookServer(pluginLogger).also { it.start(config.webhook.host, config.webhook.port, router) }
            pluginLogger.debug { "Webhook handlers registered: ${handlers.map { it.providerKey }}" }
        } catch (e: Exception) {
            // Keep the plugin running (polling/other features still work); the operator fixes the port.
            pluginLogger.error("Failed to bind webhook server on ${config.webhook.host}:${config.webhook.port}.", e)
        }
    }

    private fun setupEngagement(
        http: Http,
        database: Database,
        rewardDelivery: RewardDeliveryService,
        promo: PromoService,
        donationSuccess: DonationSuccessService,
        cardPaymentDao: CardPaymentDao,
        eventPublisher: KmdEventPublisher,
        manualCredit: ManualCreditService,
        menus: MenuService,
        bedrockForms: BedrockForms?,
    ) {
        val milestoneDao = MilestoneDao(database)
        val pointLogDao = PointLogDao(database)
        val leaderboardService = LeaderboardService(LeaderboardDao(database), scheduler, configRef, pluginLogger)
        val milestoneService = MilestoneService(
            milestoneDao, { configManager.milestones }, { configManager.serverMilestones },
            rewardDelivery, scheduler, pluginLogger,
        )
        val bossBarUi = MilestoneBossBar(
            milestoneDao, { configManager.milestones }, { configManager.serverMilestones },
            scheduler, configRef,
        )
        val discord = DiscordService(
            http, scheduler, { configManager.discord }, configRef, cardPaymentDao::findByReference, pluginLogger,
        )

        // Attach hooks now that collaborators exist.
        donationSuccess.milestoneHook = { d -> milestoneService.check(d) }
        donationSuccess.leaderboardHook = { d -> leaderboardService.invalidatePlayer(d.uuid) }
        donationSuccess.bossbarHook = { uuid -> bossBarUi.refresh(uuid) }
        donationSuccess.discordHook = { d ->
            when (d.method) {
                "card" -> discord.notifyCard(d)
                "bank" -> discord.notifyBank(d)
            }
        }
        donationSuccess.eventHook = { d -> eventPublisher.fireSuccess(d) }
        donationSuccess.auditHook = { d ->
            try {
                pointLogDao.record(
                    PointLogEntry(
                        playerUuid = d.uuid,
                        playerName = d.name,
                        amount = d.point,
                        method = d.method,
                        provider = d.provider,
                        referenceCode = d.referenceCode,
                        actor = d.actor,
                        server = configRef().serverId,
                        content = auditContent(d),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                pluginLogger.error("Point ledger write failed ref=${d.referenceCode}", e)
            }
            activityLog?.log(
                "POINT",
                "+${d.point} ${d.method} provider=${d.provider} ref=${d.referenceCode} " +
                    "player=${d.name ?: d.uuid} actor=${d.actor ?: "-"}",
            )
        }

        milestoneService.onPlayerMilestone = { d, t, p -> discord.notifyPlayerMilestone(d, t); eventPublisher.firePlayerMilestone(d, t, p) }
        milestoneService.onServerMilestone = { d, t, p -> discord.notifyServerMilestone(d, t); eventPublisher.fireServerMilestone(d, t, p) }

        server.pluginManager.registerEvents(MilestoneJoinListener(bossBarUi, milestoneService, scheduler), this)
        bossBarUi.start()

        val expansion = KmdExpansion.create(this, leaderboardService, promo)
        expansion.installIfPresent()

        val leaderboardForm = bedrockForms?.let {
            LeaderboardForm(leaderboardService, it, scheduler) { configManager.bedrockForms }
        }
        val leaderboardMenu = LeaderboardMenu(menus, leaderboardService, HeadResolver(pluginLogger), scheduler, leaderboardForm)
        getCommand("topnap")?.setExecutor(TopNapCommand(leaderboardService, leaderboardMenu, scheduler, messagesRef))
        getCommand("napbossbar")?.setExecutor(BossBarCommand(bossBarUi, configRef, messagesRef))

        this.expansion = expansion
        this.bossBar = bossBarUi
        this.leaderboard = leaderboardService

        // Expose the public API via both the static singleton and the Bukkit ServicesManager.
        val api = KingMCDonateAPIImpl(leaderboardService, manualCredit) { uuid ->
            Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
        }
        KingMCDonateAPI.set(api)
        server.servicesManager.register(KingMCDonateAPI::class.java, api, this, ServicePriority.Normal)
    }

    /** Short human-readable description of a point change for the ledger `content` column. */
    private fun auditContent(d: Donation): String = when {
        d.provider == ManualCreditService.MANUAL_PROVIDER -> "Manual credit (${d.method})"
        d.method == "card" -> "Card top-up ${d.provider}"
        d.method == "bank" -> "Bank top-up ${d.provider}"
        else -> d.method
    }

    private fun registerCommands(
        currency: CurrencyRegistry,
        card: CardSubsystem,
        bank: BankSubsystem,
        menus: MenuService,
        database: Database,
        manualCredit: ManualCreditService,
        bedrockForms: BedrockForms?,
    ) {
        val router = CommandRouter(messagesRef).apply {
            register(ReloadCommand(configManager, currency, card.providers, bank.providers, menus.registry, guiManager, bossBar, leaderboard, expansion))
            register(LichSuSubCommand(card.cardPaymentDao, scheduler, messagesRef))
            register(FakeCardSubCommand(card.service, configRef, messagesRef))
            register(FakeBankSubCommand(bank.service, messagesRef))
            register(GiveSubCommand(manualCredit, PlayerDao(database), scheduler, pluginLogger, configRef, messagesRef))
        }
        getCommand("kingmcdonate")?.apply {
            setExecutor(router)
            tabCompleter = router
        }

        val napThe = NapTheCommand(card.service, card.menu, configRef, messagesRef)
        getCommand("napthe")?.apply {
            setExecutor(napThe)
            tabCompleter = napThe
        }

        val historyForm = bedrockForms?.let {
            HistoryForm(card.cardPaymentDao, bank.bankPaymentDao, it, scheduler, messagesRef) { configManager.bedrockForms }
        }
        val historyMenu = HistoryMenu(card.cardPaymentDao, bank.bankPaymentDao, menus, scheduler, messagesRef, historyForm)
        val lichSu = LichSuNapCommand(card.cardPaymentDao, historyMenu, scheduler, messagesRef)
        getCommand("lichsunap")?.setExecutor(lichSu)

        getCommand("bank")?.setExecutor(BankCommand(bank.service, messagesRef))
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
        val webhookHandler: WebhookHandler?,
    )

    /** Bundle of bank-subsystem components shared between bootstrap and command registration. */
    private class BankSubsystem(
        val providers: BankProviderRegistry,
        val bankPaymentDao: BankPaymentDao,
        val service: BankPaymentService,
        val pollService: BankPollService,
        val qrRenderer: QrMapRenderer,
        val webhookHandler: WebhookHandler?,
    )
}
