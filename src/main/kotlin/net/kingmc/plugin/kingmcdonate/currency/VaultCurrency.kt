package net.kingmc.plugin.kingmcdonate.currency

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Vault economy adapter. Created via [create], which resolves the registered
 * [Economy] service; returns null when no economy plugin is hooked into Vault.
 * Only referenced when the Vault plugin is enabled.
 */
class VaultCurrency private constructor(private val economy: Economy) : CurrencyProvider {

    override val name = "vault"

    override fun isAvailable() = economy.isEnabled

    override fun give(uuid: UUID, amount: Long) {
        economy.depositPlayer(Bukkit.getOfflinePlayer(uuid), amount.toDouble())
    }

    override fun balance(uuid: UUID): Long = economy.getBalance(Bukkit.getOfflinePlayer(uuid)).toLong()

    companion object {
        fun create(): VaultCurrency? {
            val registration = Bukkit.getServicesManager().getRegistration(Economy::class.java) ?: return null
            return VaultCurrency(registration.provider)
        }
    }
}
