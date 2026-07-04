package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.discord.DiscordConfig
import net.kingmc.plugin.kingmcdonate.milestone.MilestoneConfig
import net.kingmc.plugin.kingmcdonate.promo.PromoConfig
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Owns `config.yml`, `messages.yml`, and the milestone/promo/discord config files: writes bundled
 * defaults on first run, loads them into immutable holders, and rebuilds all on
 * [reload]. A malformed file on reload is logged and the previous in-memory config is
 * kept; [load] (first run) propagates the failure so the plugin can disable.
 */
class ConfigManager(private val plugin: JavaPlugin, private val logger: PluginLogger) {

    @Volatile
    lateinit var config: PluginConfig
        private set

    @Volatile
    lateinit var messages: Messages
        private set

    @Volatile
    lateinit var milestones: MilestoneConfig
        private set

    @Volatile
    lateinit var serverMilestones: MilestoneConfig
        private set

    @Volatile
    lateinit var promo: PromoConfig
        private set

    @Volatile
    lateinit var discord: DiscordConfig
        private set

    @Volatile
    lateinit var bedrockForms: BedrockFormsConfig
        private set

    /** First-time load: copy defaults if absent, then parse. Throws on a parse error. */
    fun load() {
        saveDefault("config.yml")
        saveDefault("messages.yml")
        saveDefault("providers/card2k.yml")
        saveDefault("providers/thesieure.yml")
        saveDefault("providers/sepay.yml")
        saveDefault("mocnap.yml")
        saveDefault("mocnaptong.yml")
        saveDefault("khuyenmai.yml")
        saveDefault("discord.yml")
        saveDefault("bedrock-forms.yml")
        applyAndLog(parse())
    }

    /** Re-read all files. Returns false (and keeps the previous holders) on error. */
    fun reload(): Boolean = try {
        applyAndLog(parse())
        true
    } catch (e: Exception) {
        logger.error("Failed to reload config (keeping previous): ${e.message}", e)
        false
    }

    private data class Parsed(
        val config: PluginConfig,
        val messages: Messages,
        val milestones: MilestoneConfig,
        val serverMilestones: MilestoneConfig,
        val promo: PromoConfig,
        val discord: DiscordConfig,
        val bedrockForms: BedrockFormsConfig,
    )

    private fun parse(): Parsed {
        val cfgYaml = loadStrict(File(plugin.dataFolder, "config.yml"))
        val newConfig = PluginConfig(cfgYaml)
        val msgYaml = loadStrict(File(plugin.dataFolder, "messages.yml"))
        val newMessages = Messages(msgYaml, newConfig.prefix, logger)
        val player = MilestoneConfig(loadStrict(File(plugin.dataFolder, "mocnap.yml")).getConfigurationSection("milestones"))
        val server = MilestoneConfig(loadStrict(File(plugin.dataFolder, "mocnaptong.yml")).getConfigurationSection("milestones"))
        val promo = PromoConfig(loadStrict(File(plugin.dataFolder, "khuyenmai.yml")))
        val discord = DiscordConfig(loadStrict(File(plugin.dataFolder, "discord.yml")))
        val bedrockForms = BedrockFormsConfig(loadStrict(File(plugin.dataFolder, "bedrock-forms.yml")))
        return Parsed(newConfig, newMessages, player, server, promo, discord, bedrockForms)
    }

    private fun applyAndLog(parsed: Parsed) {
        config = parsed.config
        messages = parsed.messages
        milestones = parsed.milestones
        serverMilestones = parsed.serverMilestones
        promo = parsed.promo
        discord = parsed.discord
        bedrockForms = parsed.bedrockForms
        logger.debugMode = parsed.config.debug
        logger.debug {
            "Config loaded: db=${parsed.config.database.type}, currency=${parsed.config.currency.provider}, " +
                "server-id=${parsed.config.serverId}, promos=${parsed.promo.promotions.size}, discord=${parsed.discord.enabled}"
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
