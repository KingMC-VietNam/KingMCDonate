package net.kingmc.plugin.kingmcdonate.currency

import java.util.UUID

/** In-memory [CurrencyProvider] for tests; [available] is settable so a test can bring the backend back. */
class FakeCurrencyProvider(
    override val name: String = "fake",
    var available: Boolean = true,
) : CurrencyProvider {

    private val balances = HashMap<UUID, Long>()

    override fun isAvailable() = available

    override fun give(uuid: UUID, amount: Long) {
        balances[uuid] = (balances[uuid] ?: 0) + amount
    }

    override fun balance(uuid: UUID): Long = balances[uuid] ?: 0
}
