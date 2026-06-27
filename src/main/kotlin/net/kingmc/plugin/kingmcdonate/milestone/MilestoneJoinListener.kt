package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Refreshes a player's bossbar and runs a retroactive milestone check on join; clears the bar on quit. */
class MilestoneJoinListener(
    private val bossBar: MilestoneBossBar,
    private val milestoneService: MilestoneService,
    private val scheduler: Scheduler,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        scheduler.runIo { milestoneService.checkPlayer(player.uniqueId, player.name) }
        bossBar.refresh(player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        bossBar.remove(event.player.uniqueId)
    }
}
