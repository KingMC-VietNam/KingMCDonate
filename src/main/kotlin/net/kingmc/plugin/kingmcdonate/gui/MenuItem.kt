package net.kingmc.plugin.kingmcdonate.gui

import org.bukkit.inventory.ItemStack

/** An item placed in a [Gui] slot plus an optional click handler. */
class MenuItem(val item: ItemStack, val onClick: ((ClickContext) -> Unit)? = null)
