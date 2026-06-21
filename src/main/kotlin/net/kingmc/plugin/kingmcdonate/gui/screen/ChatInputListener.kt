package net.kingmc.plugin.kingmcdonate.gui.screen

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures serial then PIN from chat when anvil input is disabled (or unavailable).
 * While a prompt is active for a player their chat is intercepted (not broadcast);
 * a cancel keyword aborts. The completion runs back on the player's region thread.
 */
class ChatInputListener(
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
) : Listener {

    private enum class Stage { SERIAL, PIN }

    private class Pending(var stage: Stage, var serial: String, val onComplete: (String, String) -> Unit)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Start a serial/PIN prompt for [player]; [onComplete] receives (serial, pin). */
    fun begin(player: Player, onComplete: (String, String) -> Unit) {
        pending[player.uniqueId] = Pending(Stage.SERIAL, "", onComplete)
        messages().send(player, MessageKeys.CARD_INPUT_SERIAL)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val state = pending[event.player.uniqueId] ?: return
        event.isCancelled = true
        val player = event.player
        val text = event.message.trim()

        if (text.equals("huy", ignoreCase = true) || text.equals("cancel", ignoreCase = true)) {
            pending.remove(player.uniqueId)
            messages().send(player, MessageKeys.CARD_INPUT_CANCELLED)
            return
        }

        when (state.stage) {
            Stage.SERIAL -> {
                state.serial = text
                state.stage = Stage.PIN
                messages().send(player, MessageKeys.CARD_INPUT_PIN)
            }
            Stage.PIN -> {
                pending.remove(player.uniqueId)
                val serial = state.serial
                scheduler.runAtEntity(player) { state.onComplete(serial, text) }
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pending.remove(event.player.uniqueId)
    }
}
