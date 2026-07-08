package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.entity.Player

/** Runs every dispatched task inline on the calling thread; never touches FoliaLib. */
class DirectScheduler : Scheduler(null) {
    override fun runIo(task: Runnable) = task.run()
    override fun runNextTick(task: Runnable) = task.run()
    override fun runAtEntity(player: Player, task: Runnable) = task.run()
}

object TestSchedulers {
    fun direct(): Scheduler = DirectScheduler()
}
