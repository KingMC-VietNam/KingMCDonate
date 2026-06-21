package net.kingmc.plugin.kingmcdonate.gui.menu

import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * Raw, un-resolved definition of a menu item read from config. Strings are stored as
 * written and resolved per viewer at [build] time (so one template renders correctly
 * for every player). [content] marks the item's slots as the menu's paginated content
 * region rather than a static item.
 */
class ItemTemplate(
    val material: String,
    val name: String?,
    val lore: List<String>,
    val amount: Int,
    val glow: Boolean,
    val customModelData: Int?,
    val itemFlags: List<String>,
    val skullTexture: String?,
    val sound: String?,
    val slots: List<Int>,
    val actions: List<String>,
    val content: Boolean,
) {

    /** Resolve placeholders for [viewer] and produce the item. Material may itself be a `{token}`. */
    fun build(tokens: Map<String, String>, viewer: Player?): ItemStack {
        val resolvedMaterial = Placeholders.apply(material, tokens, viewer)
        val builder = ItemBuilder.of(resolvedMaterial)
        name?.let { builder.name(Placeholders.apply(it, tokens, viewer)) }
        if (lore.isNotEmpty()) builder.lore(lore.map { Placeholders.apply(it, tokens, viewer) })
        builder.amount(amount)
        builder.glow(glow)
        builder.customModelData(customModelData)
        builder.skullTexture(skullTexture)
        for (flag in itemFlags) runCatching { builder.flags(ItemFlag.valueOf(flag.uppercase())) }
        return builder.build()
    }

    companion object {
        fun fromConfig(section: ConfigurationSection): ItemTemplate = ItemTemplate(
            material = section.getString("material", "STONE")!!,
            name = if (section.contains("name")) section.getString("name") else null,
            lore = section.getStringList("lore"),
            amount = section.getInt("amount", 1),
            glow = section.getBoolean("glow", false),
            customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data") else null,
            itemFlags = section.getStringList("item-flags"),
            skullTexture = section.getString("skull-texture"),
            sound = section.getString("sound"),
            slots = SlotParser.parse(section.get("slots") ?: section.get("slot")),
            actions = section.getStringList("actions"),
            content = section.getBoolean("content", false),
        )
    }
}
