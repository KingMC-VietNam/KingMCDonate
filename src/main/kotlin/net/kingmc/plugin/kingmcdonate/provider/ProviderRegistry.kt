package net.kingmc.plugin.kingmcdonate.provider

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Shared selection logic for the card and bank provider registries: pick the active
 * provider from config, rebuild it on reload, and fall back fail-loud to the
 * [unavailable] sentinel so the payment layer blocks intake instead of charging
 * against a misconfigured gateway. [factory] returns null when a provider's
 * credentials are missing. (Currency has its own registry: it adds a fallback chain
 * and an availability probe, so it does not share this base.)
 */
abstract class ProviderRegistry<T : Any>(
    private val logger: PluginLogger,
    private val factory: (String) -> T?,
    private val unavailable: T,
) {

    @Volatile
    lateinit var active: T
        private set

    val isAvailable: Boolean get() = active !== unavailable

    fun load(providerName: String) {
        active = resolve(providerName)
    }

    /** Pure selection: build the requested provider or fall back to the unavailable sentinel. */
    fun resolve(providerName: String): T {
        factory(providerName)?.let {
            logger.debug { activeLog(it) }
            return it
        }
        logger.warn(unavailableLog(providerName))
        return unavailable
    }

    /** Debug line describing the freshly selected provider. */
    protected abstract fun activeLog(provider: T): String

    /** Warn line when no usable provider could be built for [providerName]. */
    protected abstract fun unavailableLog(providerName: String): String

    companion object {
        /** Load `providers/<name>.yml`, or null when the file is absent. */
        fun providerConfig(dataFolder: File, name: String): YamlConfiguration? {
            val file = File(dataFolder, "providers/$name.yml")
            return if (file.exists()) YamlConfiguration.loadConfiguration(file) else null
        }
    }
}
