package net.kingmc.plugin.kingmcdonate.leaderboard

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** Warms a joining player's leaderboard stats so their personal placeholders don't cold-start at 0. */
class LeaderboardJoinListener(
    private val leaderboard: LeaderboardService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        leaderboard.warmPlayerAsync(event.player.uniqueId)
    }
}
