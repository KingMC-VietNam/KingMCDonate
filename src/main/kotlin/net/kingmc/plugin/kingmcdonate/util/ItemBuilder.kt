package net.kingmc.plugin.kingmcdonate.util

import com.cryptomorin.xseries.XMaterial
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta

/**
 * Minimal fluent builder for [ItemStack]s. Material is resolved cross-version via
 * XSeries so 1.16.5+ names work consistently. Name and lore are colorized through
 * [Text]. Properties not supported on the running server version are skipped
 * without error.
 */
class ItemBuilder private constructor(private val item: ItemStack) {

    fun name(name: String) = editMeta { it.setDisplayName(Text.colorize(name)) }

    fun lore(lines: List<String>) = editMeta { it.lore = lines.map(Text::colorize) }

    fun lore(vararg lines: String) = lore(lines.toList())

    fun amount(amount: Int) = apply { item.amount = amount.coerceIn(1, 64) }

    fun flags(vararg flags: ItemFlag) = editMeta { it.addItemFlags(*flags) }

    /** Add an inert enchant + hide it so the item shows the enchant glint. */
    fun glow(enabled: Boolean = true) = apply {
        if (!enabled) return@apply
        editMeta {
            it.addEnchant(GLOW_ENCHANT, 1, true)
            it.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
    }

    /** Set custom-model-data; silently ignored on versions/metas that don't support it. */
    fun customModelData(data: Int?) = apply {
        if (data == null || data <= 0) return@apply
        editMeta { runCatching { it.setCustomModelData(data) } }
    }

    /** Apply a base64 skull texture to a player-head; no-op on non-skull items or older metas. */
    fun skullTexture(base64: String?) = apply {
        if (base64.isNullOrBlank()) return@apply
        editMeta { meta -> if (meta is SkullMeta) runCatching { applySkullTexture(meta, base64) } }
    }

    /** Returns a defensive copy so the builder can be reused safely. */
    fun build(): ItemStack = item.clone()

    /** Set the head profile's texture via reflection so it works on 1.16.5+ without a compile dependency on authlib. */
    private fun applySkullTexture(meta: SkullMeta, base64: String) {
        val profileClass = Class.forName("com.mojang.authlib.GameProfile")
        val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
        val profile = profileClass
            .getConstructor(java.util.UUID::class.java, String::class.java)
            .newInstance(java.util.UUID.randomUUID(), "kmd")
        val property = propertyClass
            .getConstructor(String::class.java, String::class.java)
            .newInstance("textures", base64)
        val properties = profileClass.getMethod("getProperties").invoke(profile)
        properties.javaClass.getMethod("put", Any::class.java, Any::class.java)
            .invoke(properties, "textures", property)
        val field = meta.javaClass.getDeclaredField("profile")
        field.isAccessible = true
        field.set(meta, profile)
    }

    private inline fun editMeta(block: (ItemMeta) -> Unit): ItemBuilder {
        val meta = item.itemMeta
        if (meta != null) {
            block(meta)
            item.itemMeta = meta
        }
        return this
    }

    companion object {
        /** Inert enchant used only to produce a glint; hidden via HIDE_ENCHANTS. */
        private val GLOW_ENCHANT: Enchantment = Enchantment.LURE

        fun of(material: XMaterial): ItemBuilder =
            ItemBuilder(material.parseItem() ?: ItemStack(Material.STONE))

        /** Resolve [materialName] cross-version, falling back to STONE if unknown. */
        fun of(materialName: String): ItemBuilder =
            of(XMaterial.matchXMaterial(materialName).orElse(XMaterial.STONE))
    }
}
