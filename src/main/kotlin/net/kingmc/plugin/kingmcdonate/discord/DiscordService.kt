package net.kingmc.plugin.kingmcdonate.discord

import com.google.gson.Gson
import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.payment.Donation
import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sends donation/milestone notifications to Discord. Each event resolves its hooks
 * (event-specific or default), substitutes `%PLACEHOLDER%` tokens into the raw payload
 * template, and POSTs the JSON off-thread. Card serial/PIN are masked before sending.
 */
class DiscordService(
    private val http: Http,
    private val scheduler: Scheduler,
    private val config: () -> DiscordConfig,
    private val mainConfig: () -> PluginConfig,
    private val cardLookup: (String) -> CardPayment?,
    private val logger: PluginLogger,
) {

    fun notifyCard(d: Donation) = dispatch(DiscordConfig.EVENT_CARD) { cardVars(d) }
    fun notifyBank(d: Donation) = dispatch(DiscordConfig.EVENT_BANK) { baseVars(d) }
    fun notifyPlayerMilestone(d: Donation, threshold: Long) =
        dispatch(DiscordConfig.EVENT_PLAYER_MILESTONE) { baseVars(d) + ("THRESHOLD" to threshold.toString()) }
    fun notifyServerMilestone(d: Donation, threshold: Long) =
        dispatch(DiscordConfig.EVENT_SERVER_MILESTONE) { baseVars(d) + ("THRESHOLD" to threshold.toString()) }

    private fun dispatch(event: String, vars: () -> Map<String, String>) {
        val cfg = config()
        if (!cfg.enabled) return
        val hooks = cfg.hooksFor(event).filter { it.enabled }
        if (hooks.isEmpty()) return
        val resolved = vars()
        scheduler.runIo {
            for (hook in hooks) {
                try {
                    val payload = buildPayload(hook.payload, resolved)
                    http.postJson(hook.url, gson.toJson(payload))
                } catch (e: Exception) {
                    logger.warn("Discord webhook send failed for event '$event': ${e.message}")
                }
            }
        }
    }

    private fun baseVars(d: Donation): Map<String, String> = mapOf(
        "PLAYER" to (d.name ?: d.uuid.toString()),
        "AMOUNT" to Text.formatMoney(d.amountVnd),
        "POINT" to d.point.toString(),
        "POINT_UNIT" to mainConfig().pointUnit,
        "BALANCE" to d.point.toString(),
        "METHOD" to d.method,
        "SERVER" to mainConfig().serverId,
        "TIME" to TIME.withZone(ZoneId.systemDefault()).format(Instant.now()),
        "REF" to d.referenceCode,
    )

    private fun cardVars(d: Donation): Map<String, String> {
        val visible = config().serialVisibleChars
        val card = cardLookup(d.referenceCode)
        return baseVars(d) +
            ("SERIAL" to mask(card?.serial ?: "", visible)) +
            ("PIN" to mask(card?.pin ?: "", visible))
    }

    companion object {
        private val gson = Gson()
        private val TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

        /** Replace the last [value.length - visible] characters with `*`; unchanged when visible >= length. */
        fun mask(value: String, visible: Int): String {
            if (visible >= value.length) return value
            val hiddenCount = value.length - visible
            return "*".repeat(hiddenCount) + value.substring(hiddenCount)
        }

        /** Deep-copy [template], substituting `%KEY%` in strings and converting `#RRGGBB` color to Discord int. */
        @Suppress("UNCHECKED_CAST")
        fun buildPayload(template: Map<String, Any?>, vars: Map<String, String>): Map<String, Any?> =
            template.entries.associate { (k, v) -> k to transform(k, v, vars) }

        private fun transform(key: String, value: Any?, vars: Map<String, String>): Any? = when (value) {
            is String -> if (key == "color") colorToInt(substitute(value, vars)) else substitute(value, vars)
            is Map<*, *> -> buildPayload((value as Map<String, Any?>), vars)
            is List<*> -> value.map { transform("", it, vars) }
            else -> value
        }

        private fun substitute(text: String, vars: Map<String, String>): String =
            vars.entries.fold(text) { acc, (k, v) -> acc.replace("%$k%", v) }

        private fun colorToInt(text: String): Any =
            text.removePrefix("#").let { hex -> hex.toIntOrNull(16) ?: text }
    }
}
