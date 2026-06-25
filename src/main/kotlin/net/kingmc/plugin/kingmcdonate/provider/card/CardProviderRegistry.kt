package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Selects the active [CardProvider] from config and rebuilds it on reload, mirroring
 * the currency registry. The [factory] returns null when a provider's credentials
 * are missing; selection is fail-loud — an unusable provider yields
 * [UnavailableCardProvider], so the payment layer blocks intake instead of charging
 * against a misconfigured gateway.
 */
class CardProviderRegistry(
    private val logger: PluginLogger,
    private val factory: (String) -> CardProvider?,
) {

    @Volatile
    lateinit var active: CardProvider
        private set

    val isAvailable: Boolean get() = active !== UnavailableCardProvider

    fun load(providerName: String) {
        active = resolve(providerName)
    }

    /** Pure selection: build the requested provider or fall back to the unavailable sentinel. */
    fun resolve(providerName: String): CardProvider {
        factory(providerName)?.let {
            logger.debug { "Card provider active: ${it.name} (types=${it.supportedTypes()})" }
            return it
        }
        logger.warn("Card provider '$providerName' is unavailable — card top-up is blocked until this is fixed.")
        return UnavailableCardProvider
    }

    companion object {
        /**
         * Production factory: reads `providers/<name>.yml` and constructs the adapter
         * only when its credentials are present.
         */
        fun defaultFactory(
            enabledTypes: () -> Set<CardType>,
            http: Http,
            dataFolder: File,
            logger: PluginLogger,
        ): (String) -> CardProvider? = factory@{ name ->
            val yml = providerConfig(dataFolder, name) ?: return@factory null

            // Nencer-style gateways share one adapter; they differ only by base URL.
            fun nencer(baseUrl: String): CardProvider? {
                val partnerId = yml.getString("partner-id").orEmpty()
                val partnerKey = yml.getString("partner-key").orEmpty()
                if (partnerId.isBlank() || partnerKey.isBlank() || baseUrl.isBlank()) return null
                return NencerCardProvider(name, baseUrl, http::postForm, partnerId, partnerKey, enabledTypes(), logger)
            }

            when (name) {
                NencerCardProvider.CARD2K -> nencer(NencerCardProvider.CARD2K_BASE_URL)
                NencerCardProvider.THESIEURE -> nencer(yml.getString("base-url").orEmpty())
                else -> null
            }
        }

        private fun providerConfig(dataFolder: File, name: String): YamlConfiguration? {
            val file = File(dataFolder, "providers/$name.yml")
            return if (file.exists()) YamlConfiguration.loadConfiguration(file) else null
        }
    }
}

/** Sentinel for a missing/misconfigured gateway; never charges, so intake is blocked. */
object UnavailableCardProvider : CardProvider {
    override val name = "unavailable"
    override fun supportedTypes(): Set<CardType> = emptySet()
    override fun submit(request: CardRequest): CardOutcome =
        throw IllegalStateException("No usable card provider is configured")
    override fun check(transactionId: String, request: CardRequest): CardOutcome =
        CardOutcome(PaymentStatus.WAITING, transactionId, null, "unavailable")
}
