package net.kingmc.plugin.kingmcdonate.gui.menu

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Loads every menu file under `menus/` into [MenuDefinition]s, copying the bundled
 * defaults on first run. A malformed file is logged by name and skipped so the rest
 * still load. [load] rebuilds the map atomically and is safe to call again on reload.
 */
class MenuRegistry(private val plugin: JavaPlugin, private val logger: PluginLogger) {

    @Volatile
    private var menus: Map<String, MenuDefinition> = emptyMap()

    fun load() {
        copyDefaults()
        val dir = File(plugin.dataFolder, "menus")
        val loaded = LinkedHashMap<String, MenuDefinition>()
        dir.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }?.forEach { file ->
            runCatching {
                val yaml = YamlConfiguration().apply { load(file) }
                val id = file.nameWithoutExtension
                loaded[id] = MenuDefinition.parse(id, yaml)
            }.onFailure { logger.warn("Skipping menu '${file.name}': ${it.message}") }
        }
        menus = loaded
        logger.debug { "Loaded ${menus.size} menu(s): ${menus.keys.joinToString()}" }
    }

    fun get(id: String): MenuDefinition? = menus[id]

    private fun copyDefaults() {
        for (name in DEFAULTS) {
            if (!File(plugin.dataFolder, "menus/$name").exists()) plugin.saveResource("menus/$name", false)
        }
    }

    companion object {
        private val DEFAULTS = listOf("card-type.yml", "card-price.yml", "history.yml", "topnap.yml")
    }
}
