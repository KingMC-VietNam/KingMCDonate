package net.kingmc.plugin.kingmcdonate.gui

import net.kingmc.plugin.kingmcdonate.util.Text
import net.wesjd.anvilgui.AnvilGUI
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Collects serial then PIN for a card top-up. Uses AnvilGUI by default (two chained
 * anvil prompts) and falls back to chat input when anvil is disabled in config. The
 * completion callback receives the entered (serial, pin).
 */
class CardInput(
    private val plugin: Plugin,
    private val chatInput: ChatInputListener,
) {

    fun request(player: Player, useAnvil: Boolean, onComplete: (serial: String, pin: String) -> Unit) {
        if (useAnvil) openSerialAnvil(player, onComplete) else chatInput.begin(player, onComplete)
    }

    private fun openSerialAnvil(player: Player, onComplete: (String, String) -> Unit) {
        anvil(player, "&8Nhập seri thẻ", "seri") { serial ->
            openPinAnvil(player, serial, onComplete)
        }
    }

    private fun openPinAnvil(player: Player, serial: String, onComplete: (String, String) -> Unit) {
        anvil(player, "&8Nhập mã thẻ (PIN)", "pin") { pin ->
            onComplete(serial, pin)
        }
    }

    private fun anvil(player: Player, title: String, placeholder: String, onText: (String) -> Unit) {
        AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.colorize(title))
            .text(placeholder)
            .onClick { slot, snapshot ->
                if (slot != AnvilGUI.Slot.OUTPUT) {
                    return@onClick emptyList<AnvilGUI.ResponseAction>()
                }
                val text = snapshot.text.trim()
                if (text.isEmpty()) {
                    return@onClick emptyList<AnvilGUI.ResponseAction>()
                }
                listOf(
                    AnvilGUI.ResponseAction.close(),
                    AnvilGUI.ResponseAction.run { onText(text) },
                )
            }
            .open(player)
    }
}
