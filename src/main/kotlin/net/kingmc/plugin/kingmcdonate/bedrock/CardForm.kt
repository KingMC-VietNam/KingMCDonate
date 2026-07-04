package net.kingmc.plugin.kingmcdonate.bedrock

import net.kingmc.plugin.kingmcdonate.config.BedrockFormsConfig
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.payment.card.CardPaymentService
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.CustomForm

/**
 * Builds and sends the Bedrock card top-up form: one CustomForm that collapses the Java chest
 * flow (type -> denomination -> anvil serial/PIN) into a single screen. Component order is
 * `0` type dropdown, `1` denomination dropdown, `2` warning label, `3` serial input, `4` PIN
 * input, `5` submit label — so the valid-result handler reads values by those exact indices.
 * The Cumulus callback runs off the main thread, so the charge is dispatched on the player's
 * region thread.
 */
class CardForm(
    private val service: CardPaymentService,
    private val providers: CardProviderRegistry,
    private val forms: BedrockForms,
    private val scheduler: Scheduler,
    private val config: () -> PluginConfig,
    private val formConfig: () -> BedrockFormsConfig,
) {

    /** Returns true when a form was sent (this player gets the form instead of the chest GUI). */
    fun trySend(player: Player): Boolean {
        val fc = formConfig()
        if (!FormGate.shouldUse(fc.enabled, fc.card.enabled, forms.isAvailable, forms.isBedrock(player))) return false
        val types = enabledTypes()
        val denominations = config().card.denominations.entries.sortedBy { it.key }.map { it.key to it.value }
        if (types.isEmpty() || denominations.isEmpty()) return false

        val c = fc.card
        val form = CustomForm.builder()
            .title(c.title)
            .dropdown(c.typeLabel, types.map { it.name })
            .dropdown(c.priceLabel, denominations.map { Text.formatMoney(it.first) })
            .label(c.warning)
            .input(c.serialLabel, c.serialPlaceholder)
            .input(c.pinLabel, c.pinPlaceholder)
            .label(c.submitLabel)
            .validResultHandler { response ->
                val selection = CardFormSelection.resolve(
                    enabledTypes = types,
                    denominations = denominations,
                    typeIndex = response.asDropdown(0),
                    priceIndex = response.asDropdown(1),
                    serial = response.asInput(3),
                    pin = response.asInput(4),
                )
                if (selection is CardFormSelection.Ok) {
                    scheduler.runAtEntity(player) {
                        service.submit(player, selection.type, selection.amount, selection.serial, selection.pin)
                    }
                }
            }
            .build()
        return forms.send(player, form)
    }

    /** Types the active gateway supports, in config order — mirrors the chest menu's list. */
    private fun enabledTypes(): List<CardType> {
        val supported = providers.active.supportedTypes()
        return config().card.cardTypes
            .mapNotNull { CardType.parse(it) }
            .filter { it in supported }
            .distinct()
    }
}
