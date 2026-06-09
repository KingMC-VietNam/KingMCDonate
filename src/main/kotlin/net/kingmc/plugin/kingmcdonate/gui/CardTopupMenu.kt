package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.payment.CardPaymentService
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player

/**
 * The card top-up flow: a card-type chooser opens a denomination chooser, and
 * selecting a denomination starts serial/PIN input then submits the charge. Types
 * and denominations are driven by the `card` config; only types the active gateway
 * supports are shown.
 */
class CardTopupMenu(
    private val service: CardPaymentService,
    private val providers: CardProviderRegistry,
    private val input: CardInput,
    private val config: () -> PluginConfig,
) {

    fun openTypeMenu(player: Player) {
        val types = enabledTypes()
        val gui = Gui(TITLE_TYPE, rowsFor(types.size))
        types.forEachIndexed { index, type ->
            val icon = ItemBuilder.of(MATERIAL[type] ?: "PAPER")
                .name("&a${DISPLAY[type] ?: type.name}")
                .build()
            gui.setItem(index, icon) { openPriceMenu(player, type) }
        }
        gui.open(player)
    }

    fun openPriceMenu(player: Player, type: CardType) {
        val denominations = config().card.denominations.entries.sortedBy { it.key }
        val gui = Gui(TITLE_PRICE, rowsFor(denominations.size))
        denominations.forEachIndexed { index, (amount, point) ->
            val icon = ItemBuilder.of("PAPER")
                .name("&e${Text.formatMoney(amount)}")
                .lore("&7Nhận: &a$point point")
                .build()
            gui.setItem(index, icon) { event ->
                val clicker = event.whoClicked as? Player ?: return@setItem
                clicker.closeInventory()
                input.request(clicker, config().card.useAnvil) { serial, pin ->
                    service.submit(clicker, type, amount, serial, pin)
                }
            }
        }
        gui.open(player)
    }

    private fun enabledTypes(): List<CardType> {
        val supported = providers.active.supportedTypes()
        return config().card.cardTypes
            .mapNotNull { CardType.parse(it) }
            .filter { it in supported }
            .distinct()
    }

    private fun rowsFor(count: Int): Int = ((count - 1) / 9 + 1).coerceIn(1, 6)

    companion object {
        private const val TITLE_TYPE = "&8Chọn loại thẻ"
        private const val TITLE_PRICE = "&8Chọn mệnh giá"

        private val DISPLAY = mapOf(
            CardType.VIETTEL to "Viettel",
            CardType.MOBIFONE to "Mobifone",
            CardType.VINAPHONE to "Vinaphone",
            CardType.GARENA to "Garena",
            CardType.VCOIN to "Vcoin",
            CardType.ZING to "Zing",
            CardType.GATE to "Gate",
        )

        private val MATERIAL = mapOf(
            CardType.VIETTEL to "LIME_DYE",
            CardType.MOBIFONE to "RED_DYE",
            CardType.VINAPHONE to "BLUE_DYE",
            CardType.GARENA to "ORANGE_DYE",
            CardType.VCOIN to "CYAN_DYE",
            CardType.ZING to "PURPLE_DYE",
            CardType.GATE to "YELLOW_DYE",
        )
    }
}
