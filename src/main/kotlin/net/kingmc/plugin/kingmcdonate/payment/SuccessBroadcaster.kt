package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit

/**
 * Broadcasts a success notice to every online player. Sends are dispatched per player
 * via the entity scheduler so the message is delivered on the player's region thread
 * (Folia-safe). Does nothing when the configured format is blank.
 */
class SuccessBroadcaster(
    private val scheduler: Scheduler,
    private val config: () -> PluginConfig,
) {
    fun broadcast(d: Donation) {
        val format = config().broadcast.format
        if (format.isBlank()) return
        val text = Text.colorize(
            format
                .replace("{player}", d.name ?: d.uuid.toString())
                .replace("{amount}", Text.formatMoney(d.amountVnd))
                .replace("{point}", d.point.toString())
                .replace("{method}", d.method),
        )
        for (player in Bukkit.getOnlinePlayers()) {
            scheduler.runAtEntity(player) { player.sendMessage(text) }
        }
    }
}
