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
    val webhook: WebhookConfig = WebhookConfig(root.getConfigurationSection("webhook"))

    /** How often the reward outbox is drained, in ticks. */
    val rewardDeliveryIntervalTicks: Long = root.getLong("reward-delivery-interval", 40L).coerceAtLeast(1L)

    /** A claimed-but-undelivered outbox row older than this many minutes is requeued. */
    val staleClaimMinutes: Long = root.getLong("stale-claim-minutes", 5L).coerceAtLeast(1L)

    val pointUnit: String = root.getString("point-unit", "point")!!
    val broadcast: BroadcastConfig = BroadcastConfig(root.getConfigurationSection("broadcast"))
    val firstTopup: FirstTopupConfig = FirstTopupConfig(root.getConfigurationSection("first-topup"))
    val bossbar: BossBarConfig = BossBarConfig(root.getConfigurationSection("bossbar"))
    val leaderboard: LeaderboardConfig = LeaderboardConfig(root.getConfigurationSection("leaderboard"))
    val activityLog: ActivityLogConfig = ActivityLogConfig(root.getConfigurationSection("activity-log"))

    class DatabaseConfig(section: ConfigurationSection?) {
        /** `sqlite` (default) or `mysql`. */
        val type: String = section?.getString("type", "sqlite")?.lowercase() ?: "sqlite"
        val sqliteFile: String = section?.getString("sqlite.file", "data.db") ?: "data.db"
        val mysqlHost: String = section?.getString("mysql.host", "localhost") ?: "localhost"
        val mysqlPort: Int = section?.getInt("mysql.port", 3306) ?: 3306
        val mysqlDatabase: String = section?.getString("mysql.database", "kingmcdonate") ?: "kingmcdonate"
        val mysqlUsername: String = section?.getString("mysql.username", "root") ?: "root"
        val mysqlPassword: String = section?.getString("mysql.password", "") ?: ""
        val mysqlPoolSize: Int = (section?.getInt("mysql.pool-size", 10) ?: 10).coerceAtLeast(1)
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
        /** Active card gateway: `card2k`. */
        val provider: String = section?.getString("provider", "card2k")?.lowercase() ?: "card2k"
        /** How card orders are confirmed: `poll` | `webhook` | `both`. */
        val confirmation: ConfirmationMode = ConfirmationMode.parse(section?.getString("confirmation", "poll"))
        /** Card type names enabled for top-up (resolved to `CardType` by the registry). */
        val cardTypes: List<String> = section?.getStringList("card-types") ?: emptyList()
        /** Denomination (VND) -> points granted; the single source of card points. */
        val denominations: Map<Long, Long> = readLongMap(section, "denominations")
        /** When true, new card intake is blocked with a maintenance notice; in-flight orders still settle. */
        val maintenance: Boolean = section?.getBoolean("maintenance", false) ?: false
        /** How often WAITING orders are re-polled, in seconds. */
        val pollIntervalSeconds: Long = (section?.getLong("poll-interval", 15L) ?: 15L).coerceAtLeast(1L)
        /**
         * Delay between consecutive gateway re-checks within one poll sweep, in milliseconds.
         * Spreads the per-order `check` calls so a burst of WAITING orders does not hit the
         * gateway all at once; 0 disables spacing.
         */
        val pollSpacingMillis: Long = (section?.getLong("poll-spacing-millis", 200L) ?: 200L).coerceAtLeast(0L)
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
        /** Reference-code prefix; sanitized to uppercase [A-Z0-9] and trimmed to fit the column (prefix + 10 <= 32). */
        val prefix: String = sanitizePrefix(section?.getString("prefix", "KMD") ?: "KMD")
        /** Points granted per 1000 VND transferred; bank points are computed `amount / 1000 * point-rate`. */
        val pointRate: Double = section?.getDouble("point-rate", 1.0) ?: 1.0
        /** Minimum / maximum accepted transfer amount (VND). */
        val min: Long = section?.getLong("min", 10_000L) ?: 10_000L
        val max: Long = section?.getLong("max", 50_000_000L) ?: 50_000_000L
        /** How bank orders are confirmed: `poll` | `webhook` | `both`. */
        val confirmation: ConfirmationMode = ConfirmationMode.parse(section?.getString("confirmation", "poll"))
        /** When true, new bank intake is blocked; PENDING orders still settle. */
        val maintenance: Boolean = section?.getBoolean("maintenance", false) ?: false
        /** How often PENDING orders are polled, in seconds. */
        val pollIntervalSeconds: Long = (section?.getLong("poll-interval", 20L) ?: 20L).coerceAtLeast(1L)
        /** A PENDING order older than this many minutes is marked FAILED. */
        val timeoutMinutes: Long = section?.getLong("timeout", 30L) ?: 30L

        /** Manual bank-transfer message shown to the player alongside the QR (Java and Bedrock). */
        val manualTransfer = ManualTransferConfig(section?.getConfigurationSection("manual-transfer"))

        companion object {
            private const val MAX_PREFIX_LENGTH = 22
            private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]")

            private fun sanitizePrefix(raw: String): String =
                raw.uppercase().replace(NON_ALPHANUMERIC, "").take(MAX_PREFIX_LENGTH)
        }
    }

    /**
     * Manual bank-transfer message: whether it is sent and the template [lines]. Lines carry
     * `{bank}`/`{account}`/`{amount}`/`{ref}`/`{holder}` placeholders and normal `&` colour codes;
     * a line using `{holder}` is dropped when no holder is configured on the provider.
     */
    class ManualTransferConfig(section: ConfigurationSection?) {
        val enabled: Boolean = section?.getBoolean("enabled", true) ?: true
        val lines: List<String> = section?.getStringList("lines")?.takeIf { it.isNotEmpty() } ?: DEFAULT_LINES

        companion object {
            private val DEFAULT_LINES = listOf(
                "&e&lManual bank transfer:",
                "&7Bank: &f{bank}",
                "&7Account: &f{account}",
                "&7Holder: &f{holder}",
                "&7Amount: &f{amount}",
                "&7Content: &f{ref} &c(must be exact)",
            )
        }
    }

    /**
     * Reward commands shared by every donation method (card and bank), keyed by a VND
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
        val connectTimeoutSeconds: Long = (section?.getLong("connect-timeout-seconds", 10L) ?: 10L).coerceAtLeast(1L)
        val requestTimeoutSeconds: Long = (section?.getLong("request-timeout-seconds", 30L) ?: 30L).coerceAtLeast(1L)
        /** Total attempts per request (>= 1); the loop bound, not the number of extra retries. */
        val maxRetries: Int = (section?.getInt("max-retries", 3) ?: 3).coerceAtLeast(1)
    }

    /**
     * The shared single-port webhook receiver. [enabled] is a master switch: when
     * false, every subsystem behaves as if `confirmation: poll` and no port is bound.
     * The server only actually starts when a subsystem's confirmation mode includes
     * `webhook`. [publicBaseUrl] is informational — the operator pastes
     * `<public-base-url><base-path>/<provider>` into each gateway's dashboard.
     */
    class WebhookConfig(section: ConfigurationSection?) {
        val enabled: Boolean = section?.getBoolean("enabled", true) ?: true
        val host: String = section?.getString("host", "0.0.0.0") ?: "0.0.0.0"
        val port: Int = section?.getInt("port", 9123) ?: 9123
        val basePath: String = normalizeBasePath(section?.getString("base-path", "/kmd") ?: "/kmd")
        val publicBaseUrl: String = (section?.getString("public-base-url", "") ?: "").trimEnd('/')

        private fun normalizeBasePath(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        }
    }

    class BroadcastConfig(section: ConfigurationSection?) {
        val onSuccess: Boolean = section?.getBoolean("on-success", false) ?: false
        val format: String = section?.getString("format", "") ?: ""
    }

    class FirstTopupConfig(section: ConfigurationSection?) {
        val enabled: Boolean = section?.getBoolean("enabled", false) ?: false
        val commands: List<String> = section?.getStringList("commands") ?: emptyList()
    }

    class BossBarConfig(section: ConfigurationSection?) {
        val enabled: Boolean = section?.getBoolean("enabled", true) ?: true
        /** How often the bar progress/title is refreshed, in ticks. */
        val updateIntervalTicks: Long = (section?.getLong("update-interval", 40L) ?: 40L).coerceAtLeast(1L)
        /** How long each milestone is shown before cycling to the next, in seconds. */
        val cycleIntervalSeconds: Long = (section?.getLong("cycle-interval", 6L) ?: 6L).coerceAtLeast(1L)
    }

    class LeaderboardConfig(section: ConfigurationSection?) {
        val cacheTtlSeconds: Long = (section?.getLong("cache-ttl-seconds", 60L) ?: 60L).coerceAtLeast(1L)
        val size: Int = (section?.getInt("size", 10) ?: 10).coerceIn(1, 100)
    }

    /**
     * Operational activity log written to its own rotating file (`logs/activity.*.log`).
     * Enabled by default. Rotation is size-based; [maxFiles] rotated files are kept.
     * Changes take effect on plugin restart (the file handler is opened once at enable).
     */
    class ActivityLogConfig(section: ConfigurationSection?) {
        val enabled: Boolean = section?.getBoolean("enabled", true) ?: true
        /** Max size of one log file in kilobytes before it rotates. */
        val maxSizeKb: Int = (section?.getInt("max-size-kb", 5120) ?: 5120).coerceAtLeast(1)
        /** Number of rotated files kept. */
        val maxFiles: Int = (section?.getInt("max-files", 5) ?: 5).coerceIn(1, 100)
    }
}
