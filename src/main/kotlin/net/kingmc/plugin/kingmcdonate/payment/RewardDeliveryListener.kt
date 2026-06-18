package net.kingmc.plugin.kingmcdonate.payment

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** Delivers any outstanding outbox rewards to a player as soon as they join this node. */
class RewardDeliveryListener(private val rewardDelivery: RewardDeliveryService) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        rewardDelivery.onJoin(event.player)
    }
}
