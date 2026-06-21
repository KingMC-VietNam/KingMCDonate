package net.kingmc.plugin.kingmcdonate.gui.menu

import org.bukkit.configuration.ConfigurationSection

/**
 * A parsed menu file: its title, row count, the static items placed by slot, the
 * paginated content slots, and an optional filler. The raw [root] is kept so a code
 * renderer (card/history menus) can read extra sections (e.g. per-type item templates)
 * without bloating this model. Layout→slot mapping is pure (see [charSlots]) and tested.
 */
class MenuDefinition(
    val id: String,
    val title: String,
    val rows: Int,
    val staticItems: Map<Int, ItemTemplate>,
    val contentSlots: List<Int>,
    val filler: ItemTemplate?,
    val root: ConfigurationSection,
) {

    companion object {

        /** Map each non-space layout character to the slots it occupies (row-major). */
        fun charSlots(layout: List<String>): Map<Char, List<Int>> {
            val result = LinkedHashMap<Char, MutableList<Int>>()
            layout.forEachIndexed { row, line ->
                for (col in 0 until minOf(line.length, 9)) {
                    val ch = line[col]
                    if (ch != ' ') result.getOrPut(ch) { ArrayList() }.add(row * 9 + col)
                }
            }
            return result
        }

        fun parse(id: String, root: ConfigurationSection): MenuDefinition {
            val title = root.getString("title", "&8$id")!!
            val rows = root.getInt("rows", 6).coerceIn(1, 6)
            val layout = root.getStringList("layout")
            val charToSlots = charSlots(layout)

            val templates = LinkedHashMap<String, ItemTemplate>()
            root.getConfigurationSection("items")?.let { items ->
                for (key in items.getKeys(false)) {
                    items.getConfigurationSection(key)?.let { templates[key] = ItemTemplate.fromConfig(it) }
                }
            }

            // Place layout-character items first, then let explicit slots override the same slot.
            val placements = LinkedHashMap<Int, ItemTemplate>()
            charToSlots.forEach { (ch, slots) ->
                templates[ch.toString()]?.let { template -> slots.forEach { placements[it] = template } }
            }
            templates.values.forEach { template -> template.slots.forEach { placements[it] = template } }

            val staticItems = LinkedHashMap<Int, ItemTemplate>()
            val contentSlots = ArrayList<Int>()
            placements.forEach { (slot, template) ->
                if (template.content) contentSlots.add(slot) else staticItems[slot] = template
            }
            contentSlots.sort()

            val filler = root.getConfigurationSection("filler")
                ?.takeIf { it.getBoolean("enabled", true) }
                ?.let { ItemTemplate.fromConfig(it) }

            return MenuDefinition(id, title, rows, staticItems, contentSlots, filler, root)
        }
    }
}
