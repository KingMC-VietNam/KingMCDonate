package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.provider.ProviderRegistry
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.io.File

/**
 * Selects the active [BankProvider] from config and rebuilds it on reload. Selection is
 * fail-loud — an unusable provider yields [UnavailableBankProvider], so the payment layer
 * blocks bank intake instead of issuing QR codes against a misconfigured gateway.
 */
class BankProviderRegistry(
    logger: PluginLogger,
    factory: (String) -> BankProvider?,
) : ProviderRegistry<BankProvider>(logger, factory, UnavailableBankProvider) {

    override fun activeLog(provider: BankProvider) = "Bank provider active: ${provider.name}"

    override fun unavailableLog(providerName: String) =
        "Bank provider '$providerName' is unavailable — bank top-up is blocked until this is fixed."

    companion object {
        private const val SEPAY_ACCOUNT_PLACEHOLDER = "YOUR_BANK_ACCOUNT_NUMBER"
        private const val SEPAY_TOKEN_PLACEHOLDER = "YOUR_API_TOKEN"
        private const val WEB2M_ACCOUNT_PLACEHOLDER = "YOUR_BANK_ACCOUNT_NUMBER"
        private const val WEB2M_TOKEN_PLACEHOLDER = "YOUR_WEB2M_TOKEN"

        /** Production factory: reads `providers/<name>.yml` and builds the adapter only with real credentials. */
        fun defaultFactory(http: Http, dataFolder: File, logger: PluginLogger): (String) -> BankProvider? =
            factory@{ name ->
                val yml = ProviderRegistry.providerConfig(dataFolder, name) ?: return@factory null
                when (name) {
                    SePayBankProvider.NAME -> {
                        val account = yml.getString("account-number").orEmpty()
                        val bank = yml.getString("bank").orEmpty()
                        val token = yml.getString("api-token").orEmpty()
                        val sandbox = yml.getBoolean("sandbox", false)
                        // No default: an absent key must fail closed (reject), not silently accept every webhook.
                        val webhookAuth = yml.getString("webhook-auth").orEmpty()
                        val webhookSecret = yml.getString("webhook-secret").orEmpty()
                        val webhookApiKey = yml.getString("webhook-api-key").orEmpty()
                        val accountHolder = yml.getString("account-holder").orEmpty()
                        if (account.isBlank() || account == SEPAY_ACCOUNT_PLACEHOLDER ||
                            bank.isBlank() || token.isBlank() || token == SEPAY_TOKEN_PLACEHOLDER
                        ) {
                            null
                        } else {
                            SePayBankProvider(
                                http::get, account, bank, token, sandbox, logger,
                                webhookAuth, webhookSecret, webhookApiKey, accountHolder,
                            )
                        }
                    }
                    Web2MBankProvider.NAME -> {
                        val account = yml.getString("account-number").orEmpty()
                        val bankType = BankType.parse(yml.getString("bank-type"))
                        val password = yml.getString("password").orEmpty()
                        val token = yml.getString("token").orEmpty()
                        val accountHolder = yml.getString("account-holder").orEmpty()
                        // No default: an absent key must fail closed (reject), not silently accept every webhook.
                        val webhookAuth = yml.getString("webhook-auth").orEmpty()
                        val webhookToken = yml.getString("webhook-token").orEmpty()
                        if (account.isBlank() || account == WEB2M_ACCOUNT_PLACEHOLDER ||
                            token.isBlank() || token == WEB2M_TOKEN_PLACEHOLDER ||
                            bankType == null || (!bankType.oneParam && password.isBlank())
                        ) {
                            null
                        } else {
                            Web2MBankProvider(
                                http::get, account, bankType, password, token, logger,
                                accountHolder, webhookAuth, webhookToken,
                            )
                        }
                    }
                    else -> null
                }
            }
    }
}
