package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.ConfigurationSection

/**
 * Immutable, typed view of `config.yml`. Built fresh on every load/reload so the
 * rest of the plugin can swap the whole holder atomically instead of re-reading
 * keys ad-hoc. `prefix` is kept raw (un-colorized) so callers colorize once.
 */
class PluginConfig(root: ConfigurationSection) {

    val prefix: String = root.getString("prefix", "")!!
    val debug: Boolean = root.getBoolean("debug", false)
    val serverId: String = root.getString("server-id", "default")!!
    val database: DatabaseConfig = DatabaseConfig(root.getConfigurationSection("database"))
    val currency: CurrencyConfig = CurrencyConfig(root.getConfigurationSection("currency"))

    class DatabaseConfig(section: ConfigurationSection?) {
        /** `sqlite` (default) or `mysql`. */
        val type: String = section?.getString("type", "sqlite")?.lowercase() ?: "sqlite"
        val sqliteFile: String = section?.getString("sqlite.file", "data.db") ?: "data.db"
        val mysqlHost: String = section?.getString("mysql.host", "localhost") ?: "localhost"
        val mysqlPort: Int = section?.getInt("mysql.port", 3306) ?: 3306
        val mysqlDatabase: String = section?.getString("mysql.database", "kingmcdonate") ?: "kingmcdonate"
        val mysqlUsername: String = section?.getString("mysql.username", "root") ?: "root"
        val mysqlPassword: String = section?.getString("mysql.password", "") ?: ""
        val mysqlPoolSize: Int = section?.getInt("mysql.pool-size", 10) ?: 10
    }

    class CurrencyConfig(section: ConfigurationSection?) {
        /** Active provider: `playerpoints` | `command` | `vault`. */
        val provider: String = section?.getString("provider", "playerpoints")?.lowercase() ?: "playerpoints"
        /** Fallback provider when the active one is unavailable; blank = none (block intake). */
        val fallback: String = section?.getString("fallback", "")?.lowercase() ?: ""
        /** Console commands for the Command provider; `{player}` / `{amount}` substituted. */
        val commands: List<String> = section?.getStringList("commands") ?: emptyList()
    }
}
