package net.kingmc.plugin.kingmcdonate.gui.menu

import net.kingmc.plugin.kingmcdonate.gui.ClickContext
import net.kingmc.plugin.kingmcdonate.gui.Gui
import net.kingmc.plugin.kingmcdonate.gui.GuiManager
import net.kingmc.plugin.kingmcdonate.gui.MenuItem
import net.kingmc.plugin.kingmcdonate.gui.Pagination
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.WeakHashMap

/**
 * Ties the [MenuRegistry] and [GuiManager] together: builds a [Gui] from a definition
 * (static items + filler), turns each item's config `actions` into a click handler,
 * and opens menus by id. A code renderer (card/history menus) registers a dynamic
 * opener via [registerOpener]; everything else falls back to a generic static open so
 * owners can add and link plain menus. Pagination created by a renderer is attached
 * via [attachPagination] so `page:next`/`page:prev` actions drive it.
 */
class MenuService(
    val registry: MenuRegistry,
    val manager: GuiManager,
    private val logger: PluginLogger,
) {

    private val openers = HashMap<String, (Player) -> Unit>()
    private val paginations = WeakHashMap<Gui, Pagination<*>>()

    fun registerOpener(id: String, opener: (Player) -> Unit) {
        openers[id] = opener
    }

    /** Open a menu by id: a registered dynamic opener if present, else a generic static render. */
    fun open(id: String, player: Player) {
        val opener = openers[id]
        if (opener != null) {
            opener(player)
            return
        }
        val definition = registry.get(id)
        if (definition == null) {
            logger.warn("Menu '$id' not found; open ignored.")
            return
        }
        val gui = create(definition, player, baseTokens(player))
        gui.open(player)
    }

    /** Build a GUI from [definition], placing static items (with handlers) and the filler. */
    fun create(definition: MenuDefinition, player: Player, tokens: Map<String, String>): Gui {
        val gui = manager.create(definition.title, definition.rows)
        definition.staticItems.forEach { (slot, template) -> gui.set(slot, toMenuItem(template, player, tokens)) }
        definition.filler?.let { gui.fillEmpty(it.build(tokens, player)) }
        return gui
    }

    fun attachPagination(gui: Gui, pagination: Pagination<*>) {
        paginations[gui] = pagination
    }

    /** Turn a template into a clickable [MenuItem]; handler runs its sound + actions. */
    fun toMenuItem(template: ItemTemplate, player: Player, tokens: Map<String, String>): MenuItem {
        val item = template.build(tokens, player)
        if (template.actions.isEmpty() && template.sound == null) return MenuItem(item)
        return MenuItem(item) { context ->
            context.playSound(template.sound)
            template.actions.forEach { execute(MenuAction.parse(it), context, tokens) }
        }
    }

    fun baseTokens(player: Player): Map<String, String> = mapOf("player" to player.name)

    private fun execute(action: MenuAction, context: ClickContext, tokens: Map<String, String>) {
        val arg = Placeholders.apply(action.arg, tokens, context.player)
        when (action.verb) {
            "open" -> open(arg, context.player)
            "close" -> context.close()
            "message" -> context.player.sendMessage(Text.colorize(arg))
            "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg)
            "player" -> context.player.performCommand(arg)
            "page" -> page(context.gui, arg)
            else -> logger.warn("Unknown menu action verb: '${action.verb}'")
        }
    }

    private fun page(gui: Gui, direction: String) {
        val pagination = paginations[gui] ?: return
        when (direction.lowercase()) {
            "next" -> pagination.next()
            "prev", "previous" -> pagination.previous()
        }
    }
}
