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
    val card: CardConfig = CardConfig(root.getConfigurationSection("card"))
    val bank: BankConfig = BankConfig(root.getConfigurationSection("bank"))
    val rewards: RewardsConfig = RewardsConfig(root.getConfigurationSection("rewards"))
    val http: HttpConfig = HttpConfig(root.getConfigurationSection("http"))

    /** How often the reward outbox is drained, in ticks. */
    val rewardDeliveryIntervalTicks: Long = root.getLong("reward-delivery-interval", 40L)

    /** A claimed-but-undelivered outbox row older than this many minutes is requeued. */
    val staleClaimMinutes: Long = root.getLong("stale-claim-minutes", 5L)

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

    class CardConfig(section: ConfigurationSection?) {
        /** Active card gateway: `thesieutoc` | `card2k`. */
        val provider: String = section?.getString("provider", "thesieutoc")?.lowercase() ?: "thesieutoc"
        /** Card type names enabled for top-up (resolved to `CardType` by the registry). */
        val cardTypes: List<String> = section?.getStringList("card-types") ?: emptyList()
        /** Denomination (VNĐ) -> points granted; the single source of card points. */
        val denominations: Map<Long, Long> = readLongMap(section, "denominations")
        /** When true, new card intake is blocked with a maintenance notice; in-flight orders still settle. */
        val maintenance: Boolean = section?.getBoolean("maintenance", false) ?: false
        /** How often WAITING orders are re-polled, in seconds. */
        val pollIntervalSeconds: Long = section?.getLong("poll-interval", 15L) ?: 15L
        /** A WAITING order older than this many minutes is marked FAILED. */
        val timeoutMinutes: Long = section?.getLong("timeout", 30L) ?: 30L
        /** Use AnvilGUI for serial/PIN input; false = chat input. */
        val useAnvil: Boolean = section?.getBoolean("use-anvil", true) ?: true

        private fun readLongMap(section: ConfigurationSection?, key: String): Map<Long, Long> {
            val sub = section?.getConfigurationSection(key) ?: return emptyMap()
            return sub.getKeys(false).mapNotNull { k -> k.toLongOrNull()?.let { it to sub.getLong(k) } }.toMap()
        }
    }

    class BankConfig(section: ConfigurationSection?) {
        /** Active bank gateway: `sepay`. Credentials live in `providers/<name>.yml`. */
        val provider: String = section?.getString("provider", "sepay")?.lowercase() ?: "sepay"
        /** Prefix đầu mã giao dịch; làm sạch về [A-Z0-9] hoa, cắt vừa cột (prefix + 10 ≤ 32). */
        val prefix: String = (section?.getString("prefix", "KMD") ?: "KMD")
            .uppercase().replace(Regex("[^A-Z0-9]"), "").take(22)
        /** Points granted per 1000đ transferred; bank points are computed `amount/1000 × point-rate`. */
        val pointRate: Double = section?.getDouble("point-rate", 1.0) ?: 1.0
        /** Minimum / maximum accepted transfer amount (VNĐ). */
        val min: Long = section?.getLong("min", 10_000L) ?: 10_000L
        val max: Long = section?.getLong("max", 50_000_000L) ?: 50_000_000L
        /** When true, new bank intake is blocked; PENDING orders still settle. */
        val maintenance: Boolean = section?.getBoolean("maintenance", false) ?: false
        /** How often PENDING orders are polled, in seconds. */
        val pollIntervalSeconds: Long = section?.getLong("poll-interval", 20L) ?: 20L
        /** A PENDING order older than this many minutes is marked FAILED. */
        val timeoutMinutes: Long = section?.getLong("timeout", 30L) ?: 30L
    }

    /**
     * Reward commands shared by every donation method (card and bank), keyed by a VNĐ
     * threshold. A successful top-up runs the single highest tier whose threshold is
     * `<=` the amount paid; `{player}`/`{amount}`/`{point}`/`{ref}` are substituted and
     * each line may carry a `console:`/`player:`/`op:` context prefix.
     */
    class RewardsConfig(section: ConfigurationSection?) {
        val commands: Map<Long, List<String>> = readCommandMap(section)

        /** Commands of the highest threshold `<=` [amount]; empty when none qualifies. */
        fun commandsFor(amount: Long): List<String> =
            commands.filterKeys { it <= amount }.maxByOrNull { it.key }?.value ?: emptyList()

        private fun readCommandMap(section: ConfigurationSection?): Map<Long, List<String>> {
            val sub = section?.getConfigurationSection("commands") ?: return emptyMap()
            return sub.getKeys(false).mapNotNull { k -> k.toLongOrNull()?.let { it to sub.getStringList(k) } }.toMap()
        }
    }

    class HttpConfig(section: ConfigurationSection?) {
        val connectTimeoutSeconds: Long = section?.getLong("connect-timeout-seconds", 10L) ?: 10L
        val requestTimeoutSeconds: Long = section?.getLong("request-timeout-seconds", 30L) ?: 30L
        val maxRetries: Int = section?.getInt("max-retries", 3) ?: 3
    }
}
