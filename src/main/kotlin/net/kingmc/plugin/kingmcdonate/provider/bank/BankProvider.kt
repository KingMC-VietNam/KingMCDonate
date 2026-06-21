package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment

/** The QR a gateway produces for an order: an image URL the client fetches and renders. */
data class BankQr(val imageUrl: String)

/** A transfer the gateway confirmed as matching one order, by reference, amount and gateway tx id. */
data class BankConfirmation(
    val referenceCode: String,
    val transactionId: String,
    val amount: Long,
)

/**
 * A bank gateway. [createQr] produces the payment QR for an order; [poll] lists the
 * gateway's recent incoming transfers and returns those matching the supplied orders
 * (by exact reference token/code and amount). Confirmation is by outbound polling
 * only — no webhook or bound port. Adding a gateway means a new implementation plus a
 * registry entry; the payment core does not change.
 */
interface BankProvider {

    /** Stable identifier matching the `bank.provider` config value. */
    val name: String

    /** Build the payment QR for an order of [amountVnd] carrying [referenceCode]. */
    fun createQr(amountVnd: Long, referenceCode: String): BankQr

    /** Match the gateway's recent incoming transfers against [orders]; returns the confirmed ones. */
    fun poll(orders: List<BankPayment>): List<BankConfirmation>
}

/** Sentinel for a missing/misconfigured gateway; never produces a QR, so intake is blocked. */
object UnavailableBankProvider : BankProvider {
    override val name = "unavailable"
    override fun createQr(amountVnd: Long, referenceCode: String): BankQr =
        throw IllegalStateException("No usable bank provider is configured")
    override fun poll(orders: List<BankPayment>): List<BankConfirmation> = emptyList()
}
