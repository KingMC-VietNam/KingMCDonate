package net.kingmc.plugin.kingmcdonate.webhook

import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.provider.card.CardOutcome
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.util.UUID

/**
 * Everything a card webhook handler needs to converge on the existing idempotent
 * resolution: look the order up by its reference code, and apply a gateway outcome
 * through the same `applyOutcome` the poll service uses.
 */
class CardWebhookDeps(
    val findByReference: (String) -> CardPayment?,
    val applyOutcome: (referenceCode: String, uuid: UUID, name: String?, declaredAmount: Long, outcome: CardOutcome, ownerServer: String) -> Unit,
    val logger: PluginLogger,
)

/** A card gateway that can receive callbacks; the active provider supplies its handler. */
interface CardWebhookCapable {
    fun webhookHandler(deps: CardWebhookDeps): WebhookHandler
}
