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
 */
class RewardGate(
    private val currency: CurrencyRegistry,
    private val donationSuccess: DonationSuccessService,
    private val logger: PluginLogger,
) {

    fun applyOnce(context: String, uuid: UUID, point: Long, claim: () -> Int, donation: () -> Donation) {
        if (claim() != 1) {
            logger.debug { "$context: reward already applied; skipping credit." }
            return
        }
        try {
            currency.active.give(uuid, point)
        } catch (e: Exception) {
            logger.error("$context: reward credit failed uuid=$uuid point=$point; reconcile manually.", e)
        }
        donationSuccess.onSuccess(donation())
    }
}
