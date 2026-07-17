package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment

/**
 * The QR a gateway produces for an order: an image URL the client fetches and renders, plus
 * the receiving account details ([accountNumber], [bankName], [accountHolder]) used to build
 * the manual-transfer message. Account fields default to empty for gateways that don't expose them.
 */
data class BankQr(
    val imageUrl: String,
    val accountNumber: String = "",
    val bankName: String = "",
    val accountHolder: String = "",
)

/** A transfer the gateway confirmed as matching one order, by reference, amount and gateway tx id. */
data class BankConfirmation(
    val referenceCode: String,
    val transactionId: String,
    val amount: Long,
)

/**
 * An incoming transfer that matched **no** order, with its reference text already extracted
 * (the shape of that text is gateway-specific, so only the provider can do it). The payment
 * core decides what to make of it — typically: does it name a pending order the payer got the
 * amount wrong for?
 *
 * Matching nothing is the whole point of this type. It is what makes surfacing such a transfer
 * safe: a transfer that *did* match is on its way to being credited, and must never be touched
 * by the reporting path.
 */
data class UnmatchedTransfer(
    val transactionId: String,
    val searchText: String,
    val amount: Long,
)

/**
 * One poll's outcome: the transfers that matched an order exactly, and the ones that matched
 * nothing. Every incoming transfer lands in exactly one of the two.
 */
data class BankPollResult(
    val confirmations: List<BankConfirmation>,
    val unmatched: List<UnmatchedTransfer>,
) {
    companion object {
        val EMPTY = BankPollResult(emptyList(), emptyList())
    }
}

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

    /** Match the gateway's recent incoming transfers against [orders]; splits them into matched and not. */
    fun poll(orders: List<BankPayment>): BankPollResult
}

/** Sentinel for a missing/misconfigured gateway; never produces a QR, so intake is blocked. */
object UnavailableBankProvider : BankProvider {
    override val name = "unavailable"
    override fun createQr(amountVnd: Long, referenceCode: String): BankQr =
        throw IllegalStateException("No usable bank provider is configured")
    override fun poll(orders: List<BankPayment>): BankPollResult = BankPollResult.EMPTY
}
