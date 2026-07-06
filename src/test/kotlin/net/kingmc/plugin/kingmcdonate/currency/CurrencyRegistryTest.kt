package net.kingmc.plugin.kingmcdonate.currency

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.payment.TestSchedulers
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class CurrencyRegistryTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    private fun registry(providers: Map<String, CurrencyProvider?>) =
        CurrencyRegistry(logger) { providers[it] }

    @Test
    fun `selects the requested provider when available`() {
        val fake = FakeCurrencyProvider("playerpoints")
        val result = registry(mapOf("playerpoints" to fake)).resolve("playerpoints", "command")
        assertSame(fake, result)
    }

    @Test
    fun `falls back when requested provider is missing`() {
        val fallback = FakeCurrencyProvider("command")
        val result = registry(mapOf("playerpoints" to null, "command" to fallback))
            .resolve("playerpoints", "command")
        assertSame(fallback, result)
    }

    @Test
    fun `falls back when requested provider is present but unavailable`() {
        val unavailable = FakeCurrencyProvider("vault", available = false)
        val fallback = FakeCurrencyProvider("command")
        val result = registry(mapOf("vault" to unavailable, "command" to fallback))
            .resolve("vault", "command")
        assertSame(fallback, result)
    }

    @Test
    fun `unavailable when requested missing and no fallback configured`() {
        val result = registry(mapOf("playerpoints" to null)).resolve("playerpoints", "")
        assertSame(UnavailableCurrencyProvider, result)
    }

    @Test
    fun `unavailable when both requested and fallback are missing`() {
        val result = registry(mapOf("playerpoints" to null, "command" to null))
            .resolve("playerpoints", "command")
        assertSame(UnavailableCurrencyProvider, result)
    }

    @Test
    fun `unavailable provider throws on give instead of silently no-op`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            UnavailableCurrencyProvider.give(UUID.randomUUID(), 1000)
        }
    }

    private fun currencyConfig(commands: List<String>): PluginConfig.CurrencyConfig {
        val y = YamlConfiguration()
        y.set("currency.provider", "command")
        y.set("currency.commands", commands)
        return PluginConfig.CurrencyConfig(y.getConfigurationSection("currency"))
    }

    @Test
    fun `default command factory reads live config so reload picks up edited commands`() {
        var config = currencyConfig(listOf("say A"))
        val factory = CurrencyRegistry.defaultFactory({ config }, TestSchedulers.direct(), logger)

        val before = factory("command") as CommandCurrency
        assertEquals(listOf("say A"), before.commands)

        // Simulate /kingmcdonate reload replacing the config object.
        config = currencyConfig(listOf("say B"))
        val after = factory("command") as CommandCurrency
        assertEquals(listOf("say B"), after.commands)
    }

    @Test
    fun `fake provider credits and reads back the amount`() {
        val fake = FakeCurrencyProvider()
        val uuid = UUID.randomUUID()
        fake.give(uuid, 5000)
        fake.give(uuid, 2000)
        assertEquals(7000, fake.balance(uuid))
    }
}
