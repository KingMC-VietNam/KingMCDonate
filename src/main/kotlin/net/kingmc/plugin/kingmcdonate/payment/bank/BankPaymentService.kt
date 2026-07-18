package net.kingmc.plugin.kingmcdonate.payment.bank

import net.kingmc.plugin.kingmcdonate.KingMCDonateContext
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation
import net.kingmc.plugin.kingmcdonate.provider.bank.BankProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.bank.BankQr
import net.kingmc.plugin.kingmcdonate.render.QrImage
import net.kingmc.plugin.kingmcdonate.render.QrMapRenderer
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Opens bank QR orders: validates limits/maintenance/availability, records a PENDING
 * order with this node as the owner, then fetches and renders the gateway QR off the
 * main thread. Also provides a simulated confirmation for the `fakebank` test command.
 */
class BankPaymentService(
    private val bankPaymentDao: BankPaymentDao,
    private val playerDao: PlayerDao,
    private val providers: BankProviderRegistry,
    private val currency: CurrencyRegistry,
    private val confirmService: BankConfirmService,
    private val qrRenderer: QrMapRenderer,
    private val http: Http,
    private val scheduler: Scheduler,
    private val logger: PluginLogger,
    private val config: () -> PluginConfig,
    private val messages: () -> Messages,
) {

    /** Validate and open a bank QR order for an online [player]. */
    fun open(player: Player, amount: Long) {
        val bank = config().bank
        if (bank.maintenance) {
            messages().send(player, MessageKeys.BANK_MAINTENANCE)
            return
        }
        if (!providers.isAvailable) {
            messages().send(player, MessageKeys.BANK_UNAVAILABLE)
            return
        }
        if (!currency.active.isAvailable()) {
            messages().send(player, MessageKeys.CURRENCY_UNAVAILABLE)
            return
        }
        if (amount < bank.min || amount > bank.max) {
            messages().send(
                player,
                MessageKeys.BANK_AMOUNT_RANGE,
                "min" to Text.formatMoney(bank.min),
                "max" to Text.formatMoney(bank.max),
            )
            return
        }
        spamRejection(player.uniqueId, System.currentTimeMillis())?.let { messages().send(player, it); return }

        val uuid = player.uniqueId
        val provider = providers.active
        val serverId = config().serverId
        val now = System.currentTimeMillis()

        playerDao.upsert(uuid, player.name)
        val referenceCode = bankPaymentDao.insertPending(uuid, amount, provider.name, serverId, now)
        // What the player transfers as the content: the customizable prefix glued before the plain
        // reference. The order stores only the plain ref; matching finds it by containment.
        val content = bank.prefix + referenceCode
        KingMCDonateContext.activityLogOrNull?.log(
            "TXN", "bank created ref=$referenceCode player=${player.name} amount=$amount provider=${provider.name}",
        )
        messages().send(
            player,
            MessageKeys.BANK_CREATED,
            "amount" to Text.formatMoney(amount),
            "ref" to content,
        )
        logger.debug { "Bank open ref=$referenceCode uuid=$uuid amount=$amount provider=${provider.name}" }

        scheduler.runIo {
            try {
                val qr = provider.createQr(amount, content)
                sendManualTransfer(player, qr, amount, content)
                val mapBytes = QrImage.fetchMapBytes(http, qr.imageUrl, logger)
                if (mapBytes != null) {
                    scheduler.runAtEntity(player) { qrRenderer.show(player, mapBytes) }
                }
            } catch (e: Exception) {
                logger.error("Bank $referenceCode: could not build/render QR; order still valid.", e)
            }
        }
    }

    /**
     * The message key rejecting a new order for [uuid] at [now], or null if the player may proceed —
     * the anti-spam guard, run before the PENDING insert. Bank checks only its own table, so the cap
     * and cooldown are per method (a player may have an open card order and still open a bank one).
     */
    internal fun spamRejection(uuid: UUID, now: Long): String? {
        val antiSpam = config().antiSpam
        if (antiSpam.maxInFlight > 0 && bankPaymentDao.countOpenByPlayer(uuid) >= antiSpam.maxInFlight) {
            return MessageKeys.ORDER_IN_PROGRESS
        }
        if (antiSpam.cooldownSeconds > 0) {
            val last = bankPaymentDao.latestCreatedAtByPlayer(uuid)
            if (last != null && now - last < antiSpam.cooldownSeconds * 1000) return MessageKeys.ORDER_COOLDOWN
        }
        return null
    }

    /** Send the configured manual-transfer message (Java and Bedrock) so a player can transfer by hand. */
    private fun sendManualTransfer(player: Player, qr: BankQr, amount: Long, referenceCode: String) {
        val manual = config().bank.manualTransfer
        if (!manual.enabled) return
        val lines = BankTransferMessage.build(
            manual.lines, qr.bankName, qr.accountNumber, Text.formatMoney(amount), referenceCode, qr.accountHolder,
        ).map { Text.colorize(it) }
        if (lines.isEmpty()) return
        scheduler.runAtEntity(player) { lines.forEach { player.sendMessage(it) } }
    }

    /** Simulate a confirmed transfer (admin test): record a PENDING order then run the full confirm path. */
    fun simulate(uuid: UUID, name: String, amount: Long) {
        scheduler.runIo {
            val now = System.currentTimeMillis()
            playerDao.upsert(uuid, name)
            val referenceCode =
                bankPaymentDao.insertPending(uuid, amount, FAKE_PROVIDER, config().serverId, now)
            logger.debug { "fakebank ref=$referenceCode uuid=$uuid amount=$amount" }
            confirmService.confirm(BankConfirmation(referenceCode, "$FAKE_TX_PREFIX$referenceCode", amount))
        }
    }

    companion object {
        private const val FAKE_PROVIDER = "fake"
        private const val FAKE_TX_PREFIX = "FAKE-"
    }
}
