package net.kingmc.plugin.kingmcdonate.util

import com.cryptomorin.xseries.XMaterial
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Minimal fluent builder for [ItemStack]s. Material is resolved cross-version via
 * XSeries so 1.16.5+ names work consistently. Name and lore are colorized through
 * [Text].
 */
class ItemBuilder private constructor(private val item: ItemStack) {

    fun name(name: String) = editMeta { it.setDisplayName(Text.colorize(name)) }

    fun lore(lines: List<String>) = editMeta { it.lore = lines.map(Text::colorize) }

    fun lore(vararg lines: String) = lore(lines.toList())

    fun amount(amount: Int) = apply { item.amount = amount }

    fun flags(vararg flags: org.bukkit.inventory.ItemFlag) = editMeta { it.addItemFlags(*flags) }

    /** Returns a defensive copy so the builder can be reused safely. */
    fun build(): ItemStack = item.clone()

    private inline fun editMeta(block: (ItemMeta) -> Unit): ItemBuilder {
        val meta = item.itemMeta
        if (meta != null) {
            block(meta)
            item.itemMeta = meta
        }
        return this
    }

    companion object {
        fun of(material: XMaterial): ItemBuilder =
            ItemBuilder(material.parseItem() ?: ItemStack(Material.STONE))

        /** Resolve [materialName] cross-version, falling back to STONE if unknown. */
        fun of(materialName: String): ItemBuilder =
            of(XMaterial.matchXMaterial(materialName).orElse(XMaterial.STONE))
    }
}
