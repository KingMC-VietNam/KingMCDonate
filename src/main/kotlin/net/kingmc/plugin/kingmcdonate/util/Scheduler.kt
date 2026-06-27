package net.kingmc.plugin.kingmcdonate.util

import com.tcoded.folialib.FoliaLib
import com.tcoded.folialib.wrapper.task.WrappedTask
import org.bukkit.entity.Player
import java.util.concurrent.Executors

/**
 * Minimal scheduling facade over FoliaLib. Every scheduled task in the plugin
 * goes through this class so platform branching lives in one place and we never
 * call [org.bukkit.Bukkit.getScheduler] directly (Folia-safe).
 *
 * Blocking I/O (DB queries, bank polling, Discord webhooks) runs on a
 * virtual-thread executor ([runIo], Java 21) instead of the platform thread pool.
 */
open class Scheduler(private val foliaLib: FoliaLib?) {

    private val impl get() = foliaLib!!.scheduler

    /** Virtual-thread-per-task executor for blocking I/O. */
    private val ioExecutor = Executors.newVirtualThreadPerTaskExecutor()

    /** Blocking I/O (DB / HTTP / polling) — runs on a virtual thread. */
    open fun runIo(task: Runnable) {
        ioExecutor.execute(task)
    }

    /** Short off-main async work via the platform scheduler. */
    open fun runAsync(task: Runnable) {
        impl.runAsync { task.run() }
    }

    /** Global, non-player-bound work on the next tick (GlobalRegionScheduler on Folia). */
    open fun runNextTick(task: Runnable) {
        impl.runNextTick { task.run() }
    }

    /** Work touching a single player (entity scheduler on Folia, main thread on Spigot). */
    open fun runAtEntity(player: Player, task: Runnable) {
        impl.runAtEntity(player) { task.run() }
    }

    /** Repeating sync task; delay/period in ticks. */
    fun runTimer(task: Runnable, delayTicks: Long, periodTicks: Long): WrappedTask =
        impl.runTimer(task, delayTicks, periodTicks)

    /** Repeating async task; delay/period in ticks. */
    fun runTimerAsync(task: Runnable, delayTicks: Long, periodTicks: Long): WrappedTask =
        impl.runTimerAsync(task, delayTicks, periodTicks)

    /** Cancel all scheduled tasks and shut down the I/O executor. Call on disable. */
    fun shutdown() {
        impl.cancelAllTasks()
        ioExecutor.shutdown()
    }
}
