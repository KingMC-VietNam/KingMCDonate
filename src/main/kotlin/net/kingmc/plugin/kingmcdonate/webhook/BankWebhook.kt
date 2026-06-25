package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation
import net.kingmc.plugin.kingmcdonate.util.PluginLogger

/**
 * Everything a bank webhook handler needs to converge on the existing atomic
 * confirmation: resolve a reference token to its order, and route a confirmed
 * transfer through the same `BankConfirmService.confirm` the poll service uses.
 */
class BankWebhookDeps(
    val findByReference: (String) -> BankPayment?,
    val confirm: (BankConfirmation) -> Unit,
    val logger: PluginLogger,
)

/** A bank gateway that can receive webhooks; the active provider supplies its handler. */
interface BankWebhookCapable {
    fun webhookHandler(deps: BankWebhookDeps): WebhookHandler
}
