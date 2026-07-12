package net.kingmc.plugin.kingmcdonate.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Level

class ConfigValidatorTest {

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text.trimIndent()) }

    private fun resource(name: String) = YamlConfiguration().apply {
        loadFromString(javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText())
    }

    @Test
    fun `the bundled default config files produce no issues`() {
        val issues = ConfigValidator.validate(
            resource("config.yml"),
            resource("khuyenmai.yml").getConfigurationSection("promotions"),
            resource("mocnap.yml").getConfigurationSection("milestones"),
            resource("mocnaptong.yml").getConfigurationSection("milestones"),
            resource("discord.yml"),
        )
        assertTrue(issues.isEmpty(), "shipped defaults must be clean, got $issues")
    }

    private fun validateConfig(text: String) =
        ConfigValidator.validate(yaml(text), null, null, null, null)

    private fun paths(issues: List<ConfigIssue>) = issues.map { it.path }

    @Test
    fun `a clean config yields no issues`() {
        val issues = validateConfig(
            """
            database: { type: mysql }
            currency: { provider: command }
            card: { provider: card2k, confirmation: both, poll-interval: 15, timeout: 30 }
            bank: { provider: sepay, confirmation: poll, point-rate: 1.2, min: 10000, max: 50000000, poll-interval: 20, timeout: 30 }
            webhook: { enabled: true, port: 9123 }
            """,
        )
        assertTrue(issues.isEmpty(), "expected no issues, got $issues")
    }

    @Test
    fun `an invalid confirmation mode is a warning, not silent`() {
        val issues = validateConfig("""card: { confirmation: "po;;" }""")
        val issue = issues.single { it.path == "config.yml:card.confirmation" }
        assertEquals(Level.WARNING, issue.level)
    }

    @Test
    fun `passive is a valid confirmation mode`() {
        val issues = validateConfig("""bank: { confirmation: passive }""" + "\n" + """card: { confirmation: passive }""")
        assertTrue(
            issues.none { it.path == "config.yml:bank.confirmation" || it.path == "config.yml:card.confirmation" },
            "passive must be accepted, got $issues",
        )
    }

    @Test
    fun `unknown providers and db type warn`() {
        val issues = validateConfig(
            """
            database: { type: postgres }
            currency: { provider: coins }
            card: { provider: napthe }
            bank: { provider: momo }
            """,
        )
        assertTrue(paths(issues).containsAll(
            listOf(
                "config.yml:database.type",
                "config.yml:currency.provider",
                "config.yml:card.provider",
                "config.yml:bank.provider",
            ),
        ))
        assertTrue(issues.all { it.level == Level.WARNING })
    }

    @Test
    fun `min greater than max is severe`() {
        val issue = validateConfig("bank: { min: 100000, max: 1000 }").single { it.path == "config.yml:bank.min/max" }
        assertEquals(Level.SEVERE, issue.level)
    }

    @Test
    fun `non-positive point-rate is severe`() {
        val issue = validateConfig("bank: { point-rate: 0 }").single { it.path == "config.yml:bank.point-rate" }
        assertEquals(Level.SEVERE, issue.level)
    }

    @Test
    fun `bad webhook port is severe only when the receiver is enabled`() {
        assertEquals(Level.SEVERE, validateConfig("webhook: { enabled: true, port: 70000 }").single { it.path == "config.yml:webhook.port" }.level)
        assertTrue(validateConfig("webhook: { enabled: false, port: 70000 }").none { it.path == "config.yml:webhook.port" })
    }

    @Test
    fun `non-numeric denomination and reward keys warn`() {
        val issues = validateConfig(
            """
            card: { denominations: { "50000": 500, abc: 1 } }
            rewards: { commands: { "100000": [ "say hi" ], xyz: [ "say no" ] } }
            """,
        )
        assertTrue(paths(issues).contains("config.yml:card.denominations.abc"))
        assertTrue(paths(issues).contains("config.yml:rewards.commands.xyz"))
    }

    @Test
    fun `promotions with missing rate, bad time, or inverted window warn`() {
        val promo = yaml(
            """
            promotions:
              no-rate: { from: "01/07/2026 00:00", to: "31/07/2026 23:59" }
              bad-time: { rate: 100, from: "2026-07-01", to: "31/07/2026 23:59" }
              inverted: { rate: 50, from: "31/07/2026 23:59", to: "01/07/2026 00:00" }
            """,
        ).getConfigurationSection("promotions")
        val issues = ConfigValidator.validate(yaml("{}"), promo, null, null, null)
        assertTrue(paths(issues).any { it == "khuyenmai.yml:promotions.no-rate" })
        assertTrue(paths(issues).any { it == "khuyenmai.yml:promotions.bad-time" })
        assertTrue(paths(issues).any { it == "khuyenmai.yml:promotions.inverted" })
        assertTrue(issues.all { it.level == Level.WARNING })
    }

    @Test
    fun `milestones flag bad period, non-numeric threshold, and invalid bossbar`() {
        val moc = yaml(
            """
            milestones:
              all:
                "100000": { bossbar: { color: RAINBOW, style: FANCY } }
                notnum: { message: hi }
              yearly: {}
            """,
        ).getConfigurationSection("milestones")
        val issues = ConfigValidator.validate(yaml("{}"), null, moc, null, null)
        val p = paths(issues)
        assertTrue(p.contains("mocnap.yml:milestones.yearly"))
        assertTrue(p.contains("mocnap.yml:milestones.all.notnum"))
        assertTrue(p.contains("mocnap.yml:milestones.all.100000.bossbar.color"))
        assertTrue(p.contains("mocnap.yml:milestones.all.100000.bossbar.style"))
    }

    @Test
    fun `discord enabled without a url warns, with a url does not`() {
        val without = yaml("enabled: true")
        assertTrue(ConfigValidator.validate(yaml("{}"), null, null, null, without).any { it.path == "discord.yml:default" })
        val with = yaml(
            """
            enabled: true
            default:
              main: { url: "https://discord.com/api/webhooks/x" }
            """,
        )
        assertTrue(ConfigValidator.validate(yaml("{}"), null, null, null, with).none { it.path == "discord.yml:default" })
    }
}
