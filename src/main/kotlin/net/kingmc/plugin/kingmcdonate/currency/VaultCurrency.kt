package net.kingmc.plugin.kingmcdonate.currency

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Vault economy adapter. Created via [create], which resolves the registered
 * [Economy] service; returns null when no economy plugin is hooked into Vault.
 * Only referenced when the Vault plugin is enabled.
 *
 * [give] is dispatched to the global region thread because most Vault backends are
 * not thread-safe and rewards are credited from off-main payment threads (webhook /
 * poll / submit) — this keeps the economy mutation Folia-safe.
 */
class VaultCurrency private constructor(
    private val economy: Economy,
    private val scheduler: Scheduler,
) : CurrencyProvider {

    override val name = "vault"

    override fun isAvailable() = economy.isEnabled

    override fun give(uuid: UUID, amount: Long) {
        scheduler.runNextTick { economy.depositPlayer(Bukkit.getOfflinePlayer(uuid), amount.toDouble()) }
    }

    override fun balance(uuid: UUID): Long = economy.getBalance(Bukkit.getOfflinePlayer(uuid)).toLong()

    companion object {
        fun create(scheduler: Scheduler): VaultCurrency? {
            val registration = Bukkit.getServicesManager().getRegistration(Economy::class.java) ?: return null
            return VaultCurrency(registration.provider, scheduler)
        }
    }
}
