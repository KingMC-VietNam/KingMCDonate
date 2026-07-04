package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.ConfigurationSection

/**
 * Holder for `bedrock-forms.yml`: a master toggle plus per-form toggles and plain-text
 * strings for the card, history and leaderboard Bedrock forms. Bedrock forms cannot render
 * colour/MiniMessage, so every value here is plain text. Fallback defaults are English; the
 * bundled file carries the shipped (Vietnamese) copy.
 */
class BedrockFormsConfig(root: ConfigurationSection?) {

    val enabled: Boolean = root?.getBoolean("enabled", true) ?: true
    val card = CardFormConfig(root?.getConfigurationSection("card"))
    val history = HistoryFormConfig(root?.getConfigurationSection("history"))
    val leaderboard = LeaderboardFormConfig(root?.getConfigurationSection("leaderboard"))

    class CardFormConfig(s: ConfigurationSection?) {
        val enabled = s?.getBoolean("enabled", true) ?: true
        val title = s?.getString("title") ?: "KingMCDonate - Top up card"
        val typeLabel = s?.getString("type-label") ?: "Card type"
        val priceLabel = s?.getString("price-label") ?: "Denomination"
        val warning = s?.getString("warning") ?: "Note: a wrong denomination is credited by the card's real value"
        val serialLabel = s?.getString("serial-label") ?: "Serial"
        val serialPlaceholder = s?.getString("serial-placeholder") ?: "Enter serial"
        val pinLabel = s?.getString("pin-label") ?: "PIN"
        val pinPlaceholder = s?.getString("pin-placeholder") ?: "Enter PIN"
        val submitLabel = s?.getString("submit-label") ?: "Submit to top up"
    }

    class HistoryFormConfig(s: ConfigurationSection?) {
        val enabled = s?.getBoolean("enabled", true) ?: true
        val title = s?.getString("title") ?: "Top-up history"
        val entryFormat = s?.getString("entry-format") ?: "{time} - {label} - {amount} - {status}"
        val empty = s?.getString("empty") ?: "No transactions yet"
        val close = s?.getString("close") ?: "Close"
    }

    class LeaderboardFormConfig(s: ConfigurationSection?) {
        val enabled = s?.getBoolean("enabled", true) ?: true
        val title = s?.getString("title") ?: "Top donors"
        val entryFormat = s?.getString("entry-format") ?: "#{rank} {name} - {value}"
        val empty = s?.getString("empty") ?: "No donations yet"
        val metricButton = s?.getString("metric-button") ?: "Switch metric ({metric})"
        val periodButton = s?.getString("period-button") ?: "Switch period ({period})"
        val close = s?.getString("close") ?: "Close"
        val metricLabels: Map<String, String> = labels(s?.getConfigurationSection("metric-labels"))
        val periodLabels: Map<String, String> = labels(s?.getConfigurationSection("period-labels"))

        private fun labels(sec: ConfigurationSection?): Map<String, String> =
            sec?.getKeys(false)?.associateWith { sec.getString(it) ?: it } ?: emptyMap()
    }
}
