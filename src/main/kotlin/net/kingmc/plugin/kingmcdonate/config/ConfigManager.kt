package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Owns `config.yml` and `messages.yml`: writes bundled defaults on first run,
 * loads them into immutable holders, and rebuilds both on [reload]. A malformed
 * file on reload is logged and the previous in-memory config is kept; [load]
 * (first run) propagates the failure so the plugin can disable.
 */
class ConfigManager(private val plugin: JavaPlugin, private val logger: PluginLogger) {

    lateinit var config: PluginConfig
        private set

    lateinit var messages: Messages
        private set

    /** First-time load: copy defaults if absent, then parse. Throws on a parse error. */
    fun load() {
        saveDefault("config.yml")
        saveDefault("messages.yml")
        saveDefault("providers/card2k.yml")
        saveDefault("providers/thesieure.yml")
        saveDefault("providers/sepay.yml")
        val (cfg, msg) = parse()
        applyAndLog(cfg, msg)
    }

    /** Re-read both files. Returns false (and keeps the previous holders) on error. */
    fun reload(): Boolean = try {
        val (cfg, msg) = parse()
        applyAndLog(cfg, msg)
        true
    } catch (e: Exception) {
        logger.error("Failed to reload config (keeping previous): ${e.message}", e)
        false
    }

    private fun parse(): Pair<PluginConfig, Messages> {
        val cfgYaml = loadStrict(File(plugin.dataFolder, "config.yml"))
        val newConfig = PluginConfig(cfgYaml)
        val msgYaml = loadStrict(File(plugin.dataFolder, "messages.yml"))
        val newMessages = Messages(msgYaml, newConfig.prefix, logger)
        return newConfig to newMessages
    }

    private fun applyAndLog(newConfig: PluginConfig, newMessages: Messages) {
        config = newConfig
        messages = newMessages
        logger.debugMode = newConfig.debug
        logger.debug {
            "Config loaded: db=${newConfig.database.type}, " +
                "currency=${newConfig.currency.provider}, server-id=${newConfig.serverId}"
        }
    }

    /** Load YAML, throwing on malformed content (unlike loadConfiguration which swallows). */
    private fun loadStrict(file: File): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.load(file)
        return yaml
    }

    private fun saveDefault(name: String) {
        if (!File(plugin.dataFolder, name).exists()) plugin.saveResource(name, false)
    }
}
