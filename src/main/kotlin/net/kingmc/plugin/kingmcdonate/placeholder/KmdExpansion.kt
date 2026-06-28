package net.kingmc.plugin.kingmcdonate.placeholder

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.promo.PromoService
import net.kingmc.plugin.kingmcdonate.util.Period
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Registers the `%kmd_*%` placeholders. Pure value resolution lives in [resolve] so it
 * is unit-testable; the PlaceholderAPI surface delegates to it. Registration is guarded
 * (only when PlaceholderAPI is present and not already registered) and `persist()` is
 * true so the expansion survives `/papi reload`; it is unregistered on plugin disable.
 */
class KmdExpansion private constructor(
    private val plugin: JavaPlugin?,
    private val stats: Stats,
) : PlaceholderExpansion() {

    /** Narrow view the resolver needs, so the parser can be tested without the real services. */
    interface Stats {
        fun playerStat(uuid: UUID, metric: LeaderboardDao.Metric, period: Period): Long
        fun methodTotal(uuid: UUID, method: String): Long
        fun serverTotal(): Long
        fun top(metric: LeaderboardDao.Metric, period: Period): List<LeaderboardDao.Entry>
        fun endPromo(): String
    }

    override fun getIdentifier() = "kmd"
    override fun getAuthor() = plugin?.description?.authors?.firstOrNull() ?: "KingMC"
    override fun getVersion() = plugin?.description?.version ?: "1.0"
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        resolve(player?.uniqueId, params)

    /** Resolve a placeholder; null = unknown (PlaceholderAPI leaves it untouched). */
    fun resolve(uuid: UUID?, params: String): String? {
        val p = params.lowercase()
        // Server-wide (no player needed).
        if (p == "server_total") return stats.serverTotal().toString()
        if (p == "server_total_formatted") return Text.formatMoney(stats.serverTotal())
        if (p == "end_promo") return stats.endPromo()
        // Top boards.
        if (p.startsWith("top_")) return topPlaceholder(p)
        // Per-player below this point.
        if (uuid == null) return null
        when (p) {
            "total" -> return stats.playerStat(uuid, LeaderboardDao.Metric.AMOUNT, Period.ALL).toString()
            "total_formatted" -> return Text.formatMoney(stats.playerStat(uuid, LeaderboardDao.Metric.AMOUNT, Period.ALL))
            "card_total" -> return stats.methodTotal(uuid, "card").toString()
            "bank_total" -> return stats.methodTotal(uuid, "bank").toString()
            "point_total" -> return stats.playerStat(uuid, LeaderboardDao.Metric.POINT, Period.ALL).toString()
        }
        periodOf(p, "total_")?.let { return stats.playerStat(uuid, LeaderboardDao.Metric.AMOUNT, it).toString() }
        periodOf(p, "point_total_")?.let { return stats.playerStat(uuid, LeaderboardDao.Metric.POINT, it).toString() }
        return null
    }

    private fun topPlaceholder(p: String): String? {
        // top_<type>_<rank>_(name|value)  OR  top_point_<type>_<rank>_(name|value)
        val isPoint = p.startsWith("top_point_")
        val rest = if (isPoint) p.removePrefix("top_point_") else p.removePrefix("top_")
        val parts = rest.split("_")
        if (parts.size != 3) return null
        val period = periodName(parts[0]) ?: return null
        val rank = parts[1].toIntOrNull() ?: return null
        val field = parts[2]
        val metric = if (isPoint) LeaderboardDao.Metric.POINT else LeaderboardDao.Metric.AMOUNT
        val entry = stats.top(metric, period).getOrNull(rank - 1)
            ?: return if (field == "name") "Chưa xếp hạng" else "0"
        return when (field) {
            "name" -> entry.name ?: "?"
            // Money boards format as VND; point boards are raw counts (not currency).
            "value" -> if (metric == LeaderboardDao.Metric.AMOUNT) Text.formatMoney(entry.value) else entry.value.toString()
            else -> null
        }
    }

    private fun periodOf(p: String, prefix: String): Period? =
        if (p.startsWith(prefix)) periodName(p.removePrefix(prefix)) else null

    private fun periodName(token: String): Period? = when (token) {
        "all" -> Period.ALL
        "day" -> Period.DAY
        "week" -> Period.WEEK
        "month" -> Period.MONTH
        else -> null
    }

    fun installIfPresent() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return
        if (!isRegistered) register()
    }

    fun unregisterIfPresent() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null && isRegistered) unregister()
    }

    companion object {
        /** Production factory backed by the real services. */
        fun create(plugin: JavaPlugin, leaderboard: LeaderboardService, promo: PromoService): KmdExpansion {
            val stats = object : Stats {
                override fun playerStat(uuid: UUID, metric: LeaderboardDao.Metric, period: Period) = leaderboard.playerStat(uuid, metric, period)
                override fun methodTotal(uuid: UUID, method: String) = leaderboard.methodTotal(uuid, method)
                override fun serverTotal() = leaderboard.serverTotal()
                override fun top(metric: LeaderboardDao.Metric, period: Period) = leaderboard.top(metric, period)
                override fun endPromo() = promo.endPromoFormatted(System.currentTimeMillis())
            }
            return KmdExpansion(plugin, stats)
        }

        /** Test factory with injected stats and no Bukkit plugin. */
        fun forTest(stats: Stats): KmdExpansion = KmdExpansion(null, stats)
    }
}
