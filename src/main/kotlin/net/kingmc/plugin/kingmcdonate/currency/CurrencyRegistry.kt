package net.kingmc.plugin.kingmcdonate.currency

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.Bukkit

/**
 * Selects the active [CurrencyProvider] from config and rebuilds it on reload.
 * The provider is produced by a [factory] that returns null when the requested
 * provider's backing plugin is absent — keeping plugin-specific classes off the
 * classpath until they are known to be present. Selection is fail-loud: an
 * unusable provider yields [UnavailableCurrencyProvider] (which throws on give),
 * never a silent no-op.
 */
class CurrencyRegistry(
    private val logger: PluginLogger,
    private val factory: (String) -> CurrencyProvider?,
) {

    lateinit var active: CurrencyProvider
        private set

    /** Build the active provider from current config. */
    fun load(config: PluginConfig.CurrencyConfig) {
        active = resolve(config.provider, config.fallback)
    }

    /** Pure selection: primary → fallback → unavailable, logging each downgrade. */
    fun resolve(requested: String, fallback: String): CurrencyProvider {
        factory(requested)?.takeIf { it.isAvailable() }?.let {
            logger.debug { "Currency provider active: ${it.name}" }
            return it
        }
        logger.warn("Currency provider '$requested' is unavailable.")

        if (fallback.isNotBlank()) {
            factory(fallback)?.takeIf { it.isAvailable() }?.let {
                logger.warn("Falling back to currency provider '${it.name}'.")
                return it
            }
            logger.warn("Fallback currency provider '$fallback' is also unavailable.")
        }

        logger.warn("No usable currency provider — donation rewards are blocked until this is fixed.")
        return UnavailableCurrencyProvider
    }

    companion object {
        /**
         * Production factory: constructs an adapter only when its backing plugin
         * is enabled, so referencing a missing plugin's classes never happens.
         */
        fun defaultFactory(
            currencyConfig: PluginConfig.CurrencyConfig,
            scheduler: Scheduler,
            logger: PluginLogger,
        ): (String) -> CurrencyProvider? = factory@{ name ->
            when (name) {
                "playerpoints" ->
                    if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) PlayerPointsCurrency(scheduler) else null
                "vault" ->
                    if (Bukkit.getPluginManager().isPluginEnabled("Vault")) VaultCurrency.create(scheduler) else null
                "command" ->
                    CommandCurrency(currencyConfig.commands, scheduler, logger)
                else -> null
            }
        }
    }
}
