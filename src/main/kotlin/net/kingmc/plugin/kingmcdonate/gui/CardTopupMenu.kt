package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.payment.CardPaymentService
import net.kingmc.plugin.kingmcdonate.provider.card.CardProviderRegistry
import net.kingmc.plugin.kingmcdonate.provider.card.CardType
import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player

/**
 * The card top-up flow rendered from config menus: a card-type chooser opens a
 * denomination chooser, and selecting a denomination starts serial/PIN input then
 * submits the charge. The frame (title, border, controls) comes from `card-type.yml`
 * / `card-price.yml`; the type icons and price rows are filled into each menu's
 * content region from `card-items` / `price-item`. Types and denominations are driven
 * by the `card` config; only types the active gateway supports are shown.
 */
class CardTopupMenu(
    private val service: CardPaymentService,
    private val providers: CardProviderRegistry,
    private val input: CardInput,
    private val menus: MenuService,
    private val config: () -> PluginConfig,
) {

    init {
        menus.registerOpener("card-type") { openTypeMenu(it) }
    }

    fun openTypeMenu(player: Player) {
        val definition = menus.registry.get("card-type") ?: return
        val gui = menus.create(definition, player, menus.baseTokens(player))
        val cardItems = definition.root.getConfigurationSection("card-items")
        val pagination = Pagination(gui, definition.contentSlots) { type: CardType ->
            val template = cardItems?.getConfigurationSection(type.name)
            val item = if (template != null) {
                ItemTemplate.fromConfig(template).build(emptyMap(), player)
            } else {
                ItemBuilder.of("PAPER").name("&a${type.name}").build()
            }
            MenuItem(item) { openPriceMenu(player, type) }
        }
        menus.attachPagination(gui, pagination)
        pagination.setItems(enabledTypes())
        gui.open(player)
    }

    fun openPriceMenu(player: Player, type: CardType) {
        val definition = menus.registry.get("card-price") ?: return
        val gui = menus.create(definition, player, menus.baseTokens(player))
        val priceItem = definition.root.getConfigurationSection("price-item")
        val denominations = config().card.denominations.entries.sortedBy { it.key }
        val pagination = Pagination(gui, definition.contentSlots) { entry: Map.Entry<Long, Long> ->
            val tokens = mapOf("amount" to Text.formatMoney(entry.key), "point" to entry.value.toString())
            val item = if (priceItem != null) {
                ItemTemplate.fromConfig(priceItem).build(tokens, player)
            } else {
                ItemBuilder.of("PAPER").name("&e${Text.formatMoney(entry.key)}").build()
            }
            MenuItem(item) { context ->
                context.close()
                input.request(player, config().card.useAnvil) { serial, pin ->
                    service.submit(player, type, entry.key, serial, pin)
                }
            }
        }
        menus.attachPagination(gui, pagination)
        pagination.setItems(denominations)
        gui.open(player)
    }

    private fun enabledTypes(): List<CardType> {
        val supported = providers.active.supportedTypes()
        return config().card.cardTypes
            .mapNotNull { CardType.parse(it) }
            .filter { it in supported }
            .distinct()
    }
}
