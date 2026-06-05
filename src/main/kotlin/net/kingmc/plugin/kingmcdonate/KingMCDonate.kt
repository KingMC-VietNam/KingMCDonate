package net.kingmc.plugin.kingmcdonate

import com.tcoded.folialib.FoliaLib
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.plugin.java.JavaPlugin

class KingMCDonate : JavaPlugin() {

    private lateinit var foliaLib: FoliaLib
    private lateinit var scheduler: Scheduler

    override fun onEnable() {
        foliaLib = FoliaLib(this)
        scheduler = Scheduler(foliaLib)

        val pluginLogger = PluginLogger(logger, debugMode = false)

        KingMCDonateContext.init(this, scheduler, pluginLogger)

        val platform = when {
            foliaLib.isFolia -> "Folia"
            foliaLib.isPaper -> "Paper"
            foliaLib.isSpigot -> "Spigot"
            else -> "Unknown"
        }
        pluginLogger.info("KingMCDonate enabled (platform: $platform).")
        pluginLogger.debug("Bootstrap complete; scheduler facade ready.")
    }

    override fun onDisable() {
        if (this::scheduler.isInitialized) {
            scheduler.shutdown()
        }
    }
}
