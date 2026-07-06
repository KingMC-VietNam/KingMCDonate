package net.kingmc.plugin.kingmcdonate.config

import net.kingmc.plugin.kingmcdonate.provider.bank.SePayBankProvider
import net.kingmc.plugin.kingmcdonate.provider.card.NencerCardProvider
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.configuration.ConfigurationSection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Level

/** One config problem: where it is, how bad it is, and what the operator should know. */
data class ConfigIssue(val level: Level, val path: String, val message: String)

/**
 * Value-sanity validation across the user-editable config files. Pure: it reads the raw
 * YAML sections and returns the issues it finds without logging or throwing, so it is
 * testable and the caller decides how to surface them. It only flags values that would
 * otherwise be silently corrected — an enum falling back, a number being clamped, a
 * non-numeric map key being dropped, or an entry being skipped — so a clean config
 * yields an empty list.
 *
 * Severity (3-tier by impact):
 * - SEVERE: the value breaks a subsystem (min > max, non-positive point-rate, bad port).
 * - WARNING: an invalid value that was corrected (unknown enum/provider, dropped key, skipped promo).
 * - INFO: notable-but-harmless defaults (currently none).
 */
object ConfigValidator {

    private val PROMO_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val MILESTONE_PERIODS = setOf("all", "day", "week", "month")
    private val CONFIRMATION = ConfirmationMode.entries.map { it.name.lowercase() }.toSet()
    private val DB_TYPES = setOf("sqlite", "mysql")
    private val CURRENCY_PROVIDERS = setOf("playerpoints", "command", "vault")
    private val CARD_PROVIDERS = setOf(NencerCardProvider.CARD2K, NencerCardProvider.THESIEURE)
    private val BANK_PROVIDERS = setOf(SePayBankProvider.NAME)
    private val BAR_COLORS = BarColor.values().map { it.name }.toSet()
    private val BAR_STYLES = BarStyle.values().map { it.name }.toSet()

    fun validate(
        config: ConfigurationSection,
        promotions: ConfigurationSection?,
        playerMilestones: ConfigurationSection?,
        serverMilestones: ConfigurationSection?,
        discord: ConfigurationSection?,
    ): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        validateConfig(config, issues)
        validatePromotions(promotions, issues)
        validateMilestones(playerMilestones, "mocnap.yml", issues)
        validateMilestones(serverMilestones, "mocnaptong.yml", issues)
        validateDiscord(discord, issues)
        return issues
    }

    private fun validateConfig(c: ConfigurationSection, issues: MutableList<ConfigIssue>) {
        checkEnum(c, "config.yml", "database.type", DB_TYPES, "sqlite", issues)
        checkEnum(c, "config.yml", "currency.provider", CURRENCY_PROVIDERS, "playerpoints", issues)
        checkEnum(c, "config.yml", "card.provider", CARD_PROVIDERS, "card2k", issues)
        checkEnum(c, "config.yml", "bank.provider", BANK_PROVIDERS, "sepay", issues)
        checkEnum(c, "config.yml", "card.confirmation", CONFIRMATION, "poll", issues)
        checkEnum(c, "config.yml", "bank.confirmation", CONFIRMATION, "poll", issues)

        // Numeric sanity: values that break a subsystem when non-positive / inverted.
        if (c.isSet("bank.min") && c.isSet("bank.max") && c.getLong("bank.min") > c.getLong("bank.max")) {
            issues += ConfigIssue(
                Level.SEVERE, "config.yml:bank.min/max",
                "bank.min (${c.getLong("bank.min")}) > bank.max (${c.getLong("bank.max")}) — mọi giao dịch bank sẽ bị từ chối.",
            )
        }
        checkPositive(c, "config.yml", "bank.point-rate", Level.SEVERE, "sẽ không cộng điểm nào cho nạp bank", issues)
        checkPositive(c, "config.yml", "card.poll-interval", Level.WARNING, "đã kẹp về tối thiểu 1 giây", issues)
        checkPositive(c, "config.yml", "bank.poll-interval", Level.WARNING, "đã kẹp về tối thiểu 1 giây", issues)
        checkPositive(c, "config.yml", "card.timeout", Level.WARNING, "đơn thẻ sẽ hết hạn ngay lập tức", issues)
        checkPositive(c, "config.yml", "bank.timeout", Level.WARNING, "đơn bank sẽ hết hạn ngay lập tức", issues)

        // Webhook port only matters when the receiver is enabled.
        if (c.getBoolean("webhook.enabled", true)) {
            val port = c.getInt("webhook.port", 9123)
            if (port !in 1..65535) {
                issues += ConfigIssue(Level.SEVERE, "config.yml:webhook.port", "port $port ngoài khoảng 1-65535 — không mở được webhook.")
            }
        }

        checkNumericKeys(c.getConfigurationSection("card.denominations"), "config.yml:card.denominations", "mệnh giá", issues)
        checkNumericKeys(c.getConfigurationSection("rewards.commands"), "config.yml:rewards.commands", "mốc thưởng", issues)
    }

    private fun validatePromotions(section: ConfigurationSection?, issues: MutableList<ConfigIssue>) {
        section ?: return
        for (name in section.getKeys(false)) {
            val entry = section.getConfigurationSection(name) ?: continue
            val path = "khuyenmai.yml:promotions.$name"
            if (entry.get("rate") !is Number) {
                issues += ConfigIssue(Level.WARNING, path, "thiếu 'rate' hoặc rate không phải số — khuyến mãi bị bỏ qua.")
            }
            val from = parsePromoTime(entry.getString("from"))
            val to = parsePromoTime(entry.getString("to"))
            if (from == null) issues += ConfigIssue(Level.WARNING, path, "'from' sai định dạng (cần dd/MM/yyyy HH:mm) — khuyến mãi bị bỏ qua.")
            if (to == null) issues += ConfigIssue(Level.WARNING, path, "'to' sai định dạng (cần dd/MM/yyyy HH:mm) — khuyến mãi bị bỏ qua.")
            if (from != null && to != null && from > to) {
                issues += ConfigIssue(Level.WARNING, path, "'from' muộn hơn 'to' — khoảng khuyến mãi rỗng, không bao giờ áp dụng.")
            }
        }
    }

    private fun validateMilestones(section: ConfigurationSection?, file: String, issues: MutableList<ConfigIssue>) {
        section ?: return
        for (period in section.getKeys(false)) {
            if (period.lowercase() !in MILESTONE_PERIODS) {
                issues += ConfigIssue(Level.WARNING, "$file:milestones.$period", "kỳ không hợp lệ (chỉ all/day/week/month) — nhóm này bị bỏ qua.")
                continue
            }
            val periodSection = section.getConfigurationSection(period) ?: continue
            for (key in periodSection.getKeys(false)) {
                val path = "$file:milestones.$period.$key"
                if (key.toLongOrNull() == null) {
                    issues += ConfigIssue(Level.WARNING, path, "mốc '$key' không phải số VND — mốc này bị bỏ qua.")
                    continue
                }
                val bar = periodSection.getConfigurationSection(key)?.getConfigurationSection("bossbar") ?: continue
                bar.getString("color")?.let {
                    if (it.uppercase() !in BAR_COLORS) issues += ConfigIssue(Level.WARNING, "$path.bossbar.color", "màu '$it' không hợp lệ — dùng GREEN.")
                }
                bar.getString("style")?.let {
                    if (it.uppercase() !in BAR_STYLES) issues += ConfigIssue(Level.WARNING, "$path.bossbar.style", "style '$it' không hợp lệ — dùng SEGMENTED_10.")
                }
            }
        }
    }

    private fun validateDiscord(section: ConfigurationSection?, issues: MutableList<ConfigIssue>) {
        section ?: return
        if (!section.getBoolean("enabled", false)) return
        val default = section.getConfigurationSection("default")
        val hasUrl = default?.getKeys(false).orEmpty().any { !default!!.getConfigurationSection(it)?.getString("url").isNullOrBlank() }
        if (!hasUrl) {
            issues += ConfigIssue(Level.WARNING, "discord.yml:default", "Discord đang bật nhưng chưa có webhook url nào trong 'default' — thông báo sẽ không gửi được.")
        }
    }

    private fun checkEnum(
        c: ConfigurationSection,
        file: String,
        path: String,
        valid: Set<String>,
        default: String,
        issues: MutableList<ConfigIssue>,
    ) {
        val raw = c.getString(path)?.takeIf { it.isNotBlank() } ?: return
        if (raw.lowercase() !in valid) {
            issues += ConfigIssue(Level.WARNING, "$file:$path", "giá trị '$raw' không hợp lệ (chấp nhận: ${valid.joinToString("/")}); dùng '$default'.")
        }
    }

    private fun checkPositive(
        c: ConfigurationSection,
        file: String,
        path: String,
        level: Level,
        consequence: String,
        issues: MutableList<ConfigIssue>,
    ) {
        if (!c.isSet(path)) return
        if (c.getDouble(path) <= 0.0) {
            issues += ConfigIssue(level, "$file:$path", "giá trị phải > 0 — $consequence.")
        }
    }

    private fun checkNumericKeys(section: ConfigurationSection?, path: String, label: String, issues: MutableList<ConfigIssue>) {
        section ?: return
        for (key in section.getKeys(false)) {
            if (key.toLongOrNull() == null) {
                issues += ConfigIssue(Level.WARNING, "$path.$key", "$label '$key' không phải số — mục này bị bỏ qua.")
            }
        }
    }

    private fun parsePromoTime(text: String?): LocalDateTime? = text?.let {
        runCatching { LocalDateTime.parse(it.trim(), PROMO_FORMAT) }.getOrNull()
    }
}
