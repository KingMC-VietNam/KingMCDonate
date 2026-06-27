package net.kingmc.plugin.kingmcdonate.milestone

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Periods
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One BossBar per online player that cycles through the next uncompleted player and
 * server milestones. Numbers are read off-thread; the bar is created/updated on the
 * player's region thread (Folia-safe). Players may hide their bar (kept in memory
 * per node). Disabled globally via `bossbar.enabled`.
 */
class MilestoneBossBar(
    private val dao: MilestoneDao,
    private val playerMilestones: () -> MilestoneConfig,
    private val serverMilestones: () -> MilestoneConfig,
    private val scheduler: Scheduler,
    private val config: () -> PluginConfig,
) {

    private data class Target(val scope: String, val period: Period, val threshold: Long, val current: Long, val color: BarColor, val style: BarStyle, val serverWide: Boolean)

    private val bars = ConcurrentHashMap<UUID, BossBar>()
    private val hidden = ConcurrentHashMap.newKeySet<UUID>()
    private val cycle = ConcurrentHashMap<UUID, Int>()

    fun start() {
        val period = config().bossbar.updateIntervalTicks
        scheduler.runTimerAsync({ scheduler.runIo { tick() } }, period, period)
    }

    fun toggle(player: Player): Boolean {
        val uuid = player.uniqueId
        return if (hidden.add(uuid)) {
            remove(uuid)
            false // now hidden
        } else {
            hidden.remove(uuid)
            true // now visible
        }
    }

    fun refresh(uuid: UUID) { /* next tick repaints; nothing to do eagerly */ }

    fun remove(uuid: UUID) {
        bars.remove(uuid)?.let { bar ->
            Bukkit.getPlayer(uuid)?.let { p -> scheduler.runAtEntity(p) { bar.removeAll() } }
        }
        cycle.remove(uuid)
    }

    fun stop() {
        for (uuid in bars.keys.toList()) remove(uuid)
    }

    private fun tick() {
        if (!config().bossbar.enabled) return
        val cycleEvery = (config().bossbar.cycleIntervalSeconds * 20 / config().bossbar.updateIntervalTicks).coerceAtLeast(1)
        for (player in Bukkit.getOnlinePlayers()) {
            val uuid = player.uniqueId
            if (uuid in hidden) continue
            val targets = targetsFor(uuid)
            if (targets.isEmpty()) {
                remove(uuid)
                continue
            }
            val step = cycle.merge(uuid, 1) { old, _ -> old + 1 } ?: 1
            val index = (step / cycleEvery) % targets.size
            paint(player, targets[index.toInt()])
        }
    }

    private fun targetsFor(uuid: UUID): List<Target> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Target>()
        nextTarget(uuid, playerMilestones(), serverWide = false, now)?.let { out.add(it) }
        nextTarget(uuid, serverMilestones(), serverWide = true, now)?.let { out.add(it) }
        return out
    }

    private fun nextTarget(uuid: UUID, cfg: MilestoneConfig, serverWide: Boolean, now: Long): Target? {
        for (period in Period.entries) {
            val periodKey = Periods.key(period, now)
            val current = if (serverWide) dao.serverTotal(period, periodKey) else dao.playerTotal(uuid, period, periodKey)
            val next = cfg.forPeriod(period).firstOrNull { it.threshold > current && it.bossBar.enabled } ?: continue
            return Target(
                if (serverWide) MilestoneDao.SCOPE_SERVER else MilestoneDao.SCOPE_PLAYER,
                period, next.threshold, current, parseColor(next.bossBar.color), parseStyle(next.bossBar.style), serverWide,
            )
        }
        return null
    }

    private fun paint(player: Player, target: Target) {
        // Reuse the total already read by nextTarget; a one-tick-stale progress bar is fine
        // and halves the per-player DB reads each tick.
        val current = target.current
        val progress = (current.toDouble() / target.threshold).coerceIn(0.0, 1.0)
        val label = if (target.serverWide) "&6[Server]" else "&a[Cá nhân]"
        val title = Text.colorize(
            "$label &f${Text.formatMoney(current)} &7/ &e${Text.formatMoney(target.threshold)}",
        )
        scheduler.runAtEntity(player) {
            val bar = bars.computeIfAbsent(player.uniqueId) {
                Bukkit.createBossBar(title, target.color, target.style)
            }
            bar.color = target.color
            bar.style = target.style
            bar.setTitle(title)
            bar.progress = progress
            if (!bar.players.contains(player)) bar.addPlayer(player)
            bar.isVisible = true
        }
    }

    private fun parseColor(name: String): BarColor =
        runCatching { BarColor.valueOf(name.uppercase()) }.getOrDefault(BarColor.GREEN)

    private fun parseStyle(name: String): BarStyle =
        runCatching { BarStyle.valueOf(name.uppercase()) }.getOrDefault(BarStyle.SEGMENTED_10)
}
