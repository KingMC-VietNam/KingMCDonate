package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.provider.ProviderRegistry
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.io.File

/**
 * Selects the active [CardProvider] from config and rebuilds it on reload. Selection is
 * fail-loud — an unusable provider yields [UnavailableCardProvider], so the payment layer
 * blocks intake instead of charging against a misconfigured gateway.
 */
class CardProviderRegistry(
    logger: PluginLogger,
    factory: (String) -> CardProvider?,
) : ProviderRegistry<CardProvider>(logger, factory, UnavailableCardProvider) {

    override fun activeLog(provider: CardProvider) =
        "Card provider active: ${provider.name} (types=${provider.supportedTypes()})"

    override fun unavailableLog(providerName: String) =
        "Card provider '$providerName' is unavailable — card top-up is blocked until this is fixed."

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
            val yml = ProviderRegistry.providerConfig(dataFolder, name) ?: return@factory null

            // Nencer-style gateways share one adapter; they differ only by base URL.
            fun nencer(baseUrl: String): CardProvider? {
                val partnerId = yml.getString("partner-id").orEmpty()
                val partnerKey = yml.getString("partner-key").orEmpty()
                if (partnerId.isBlank() || partnerKey.isBlank() || baseUrl.isBlank()) return null
                return NencerCardProvider(name, baseUrl, http::postForm, partnerId, partnerKey, enabledTypes(), logger)
            }

            when (name) {
                NencerCardProvider.CARD2K -> {
                    val sandbox = yml.getBoolean("sandbox", false)
                    if (sandbox) {
                        logger.warn(
                            "card2k SANDBOX mode active — charges go to " +
                                "${NencerCardProvider.CARD2K_SANDBOX_BASE_URL}, not real cards. Set sandbox: false for production.",
                        )
                    }
                    nencer(if (sandbox) NencerCardProvider.CARD2K_SANDBOX_BASE_URL else NencerCardProvider.CARD2K_BASE_URL)
                }
                NencerCardProvider.THESIEURE -> nencer(yml.getString("base-url").orEmpty())
                else -> null
            }
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
