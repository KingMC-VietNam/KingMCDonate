package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation
import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * Everything a bank webhook handler needs to converge on the existing atomic
 * confirmation: resolve the transfer text to its PENDING order by containment plus
 * exact amount (mirroring the poll match rule), and route a confirmed transfer
 * through the same `BankConfirmService.confirm` the poll service uses.
 */
class BankWebhookDeps(
    val findPendingByContainedReference: (haystack: String, amount: Long) -> BankPayment?,
    val confirm: (BankConfirmation) -> Unit,
    val logger: PluginLogger,
)

/** A bank gateway that can receive webhooks; the active provider supplies its handler. */
interface BankWebhookCapable {
    fun webhookHandler(deps: BankWebhookDeps): WebhookHandler
}
