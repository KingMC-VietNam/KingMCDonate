package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Selects the active [BankProvider] from config and rebuilds it on reload, mirroring
 * the card and currency registries. The [factory] returns null when credentials are
 * missing or placeholder; selection is fail-loud — an unusable provider yields
 * [UnavailableBankProvider], so the payment layer blocks bank intake instead of
 * issuing QR codes against a misconfigured gateway.
 */
class BankProviderRegistry(
    private val logger: PluginLogger,
    private val factory: (String) -> BankProvider?,
) {

    lateinit var active: BankProvider
        private set

    val isAvailable: Boolean get() = active !== UnavailableBankProvider

    fun load(providerName: String) {
        active = resolve(providerName)
    }

    /** Pure selection: build the requested provider or fall back to the unavailable sentinel. */
    fun resolve(providerName: String): BankProvider {
        factory(providerName)?.let {
            logger.debug { "Bank provider active: ${it.name}" }
            return it
        }
        logger.warn("Bank provider '$providerName' is unavailable — bank top-up is blocked until this is fixed.")
        return UnavailableBankProvider
    }

    companion object {
        private const val SEPAY_ACCOUNT_PLACEHOLDER = "YOUR_BANK_ACCOUNT_NUMBER"
        private const val SEPAY_TOKEN_PLACEHOLDER = "YOUR_API_TOKEN"

        /** Production factory: reads `providers/<name>.yml` and builds the adapter only with real credentials. */
        fun defaultFactory(http: Http, dataFolder: File, logger: PluginLogger): (String) -> BankProvider? =
            factory@{ name ->
                val yml = providerConfig(dataFolder, name) ?: return@factory null
                when (name) {
                    SePayBankProvider.NAME -> {
                        val account = yml.getString("account-number").orEmpty()
                        val bank = yml.getString("bank").orEmpty()
                        val token = yml.getString("api-token").orEmpty()
                        val sandbox = yml.getBoolean("sandbox", false)
                        if (account.isBlank() || account == SEPAY_ACCOUNT_PLACEHOLDER ||
                            bank.isBlank() || token.isBlank() || token == SEPAY_TOKEN_PLACEHOLDER
                        ) {
                            null
                        } else {
                            SePayBankProvider(http::get, account, bank, token, sandbox, logger)
                        }
                    }
                    else -> null
                }
            }

        private fun providerConfig(dataFolder: File, name: String): YamlConfiguration? {
            val file = File(dataFolder, "providers/$name.yml")
            return if (file.exists()) YamlConfiguration.loadConfiguration(file) else null
        }
    }
}
