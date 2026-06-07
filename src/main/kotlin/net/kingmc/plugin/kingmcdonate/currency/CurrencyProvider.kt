package net.kingmc.plugin.kingmcdonate.currency

import java.util.UUID

/**
 * Abstraction over the system that pays out rewards. Operates on a player
 * **UUID** so rewards can be granted while the player is offline. Concrete
 * adapters (PlayerPoints, Command, Vault) only need to be considered when their
 * backing plugin is actually present — see [CurrencyRegistry].
 */
interface CurrencyProvider {

    /** Stable identifier matching the config value (e.g. `playerpoints`). */
    val name: String

    /** Whether this provider can currently pay out. */
    fun isAvailable(): Boolean

    /** Credit [amount] to the player. */
    fun give(uuid: UUID, amount: Long)

    /** Current balance, or 0 if this provider exposes none. */
    fun balance(uuid: UUID): Long
}

/**
 * Sentinel used when no configured provider is usable. It never silently
 * succeeds: [give] throws so the payment layer blocks intake instead of dropping
 * a reward.
 */
object UnavailableCurrencyProvider : CurrencyProvider {
    override val name = "unavailable"
    override fun isAvailable() = false
    override fun give(uuid: UUID, amount: Long): Unit =
        throw IllegalStateException("No usable currency provider is configured")
    override fun balance(uuid: UUID): Long = 0
}
