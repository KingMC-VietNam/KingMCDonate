package net.kingmc.plugin.kingmcdonate.payment.reward

import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.DonationSuccessService
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.util.UUID

/**
 * The single reward-once gate shared by every payment path (card, bank, manual). Given a
 * conditional [claim] that flips `reward_applied` 0→1, it credits the external points and
 * runs post-success work at most once: the resolving caller and any reconcile pass compete
 * on [claim], and only the winner (return value 1) credits. A credit failure is logged for
 * manual reconciliation but never aborts post-success work — the atomic status/totals commit
 * already happened. [donation] is built lazily so its collaborators (e.g. a name lookup) only
 * run on the winning path.
 *
 * Whether [CurrencyProvider.give] returned normally is passed to [DonationSuccessService.onSuccess],
 * which books the ledger row only when it did — so the ledger never claims points a credit visibly
 * refused to make. That is a narrower promise than it looks: the real providers hand the credit to
 * the main thread and return, so a backend that fails *later* still books a row. What the ledger
 * does witness reliably is that this method reached its end, which is what makes the at-most-once
 * loss discoverable: the claim is taken before the credit, so a node that dies in between leaves
 * `reward_applied = 1` on an order no reconcile pass will ever revisit. Its missing ledger row is
 * the only trace, and `/kingmcdonate reconcile` reports exactly that.
 */
class RewardGate(
    private val currency: CurrencyRegistry,
    private val donationSuccess: DonationSuccessService,
    private val logger: PluginLogger,
) {

    fun applyOnce(context: String, uuid: UUID, point: Long, claim: () -> Int, donation: () -> Donation) {
        // Never claim what cannot be credited. The claim is the at-most-once token: taking it while
        // the backend is down would spend it on a credit that cannot happen, and the reconcile pass
        // only ever looks for `reward_applied = 0` — so the order would look paid and never retry.
        if (!currency.active.isAvailable()) {
            logger.warn("$context: currency backend unavailable; credit left unclaimed for a later pass.")
            return
        }
        if (claim() != 1) {
            logger.debug { "$context: reward already applied; skipping credit." }
            return
        }
        val credited = try {
            currency.active.give(uuid, point)
            true
        } catch (e: Exception) {
            logger.error("$context: reward credit failed uuid=$uuid point=$point; reconcile manually.", e)
            false
        }
        donationSuccess.onSuccess(donation(), credited)
    }
}
