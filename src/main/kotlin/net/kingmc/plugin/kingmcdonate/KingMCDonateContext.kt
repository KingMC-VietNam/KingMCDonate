package net.kingmc.plugin.kingmcdonate

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler

/**
 * Process-wide registry of bootstrap-level components, populated in
 * [KingMCDonate.onEnable]. Later phases (config, database, providers, services)
 * register and look up shared components here instead of threading the plugin
 * instance through every constructor.
 */
object KingMCDonateContext {

    lateinit var plugin: KingMCDonate
        private set

    lateinit var scheduler: Scheduler
        private set

    lateinit var logger: PluginLogger
        private set

    internal fun init(plugin: KingMCDonate, scheduler: Scheduler, logger: PluginLogger) {
        this.plugin = plugin
        this.scheduler = scheduler
        this.logger = logger
    }
}
