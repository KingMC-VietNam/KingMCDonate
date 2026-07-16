package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookCapable
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

class BankProviderRegistryTest {

    @TempDir
    lateinit var dataFolder: File
    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private val http = Http(1, 1, 1, logger)

    private fun writeWeb2mYml(
        account: String = "0123456789",
        bankType: String = "ACB",
        password: String = "secret",
        token: String = "tok123",
        webhookAuth: String = "bearer",
        webhookToken: String = "whtok",
    ) {
        val dir = File(dataFolder, "providers").apply { mkdirs() }
        File(dir, "web2m.yml").writeText(
            """
            account-number: "$account"
            account-holder: "Nguyen Van A"
            bank-type: "$bankType"
            password: "$password"
            token: "$token"
            webhook-auth: "$webhookAuth"
            webhook-token: "$webhookToken"
            """.trimIndent(),
        )
    }

    private fun resolve(): BankProvider {
        val registry = BankProviderRegistry(logger, BankProviderRegistry.defaultFactory(http, dataFolder, logger))
        return registry.resolve(Web2MBankProvider.NAME)
    }

    @Test
    fun `valid config yields a webhook-capable Web2M provider`() {
        writeWeb2mYml()
        val provider = resolve()
        assertTrue(provider is Web2MBankProvider)
        assertTrue(provider is BankWebhookCapable)
    }

    @Test
    fun `a one-param bank needs no password`() {
        writeWeb2mYml(bankType = "MBBANK_OPENAPI", password = "")
        assertTrue(resolve() is Web2MBankProvider)
    }

    @Test
    fun `a non-one-param bank with no password is unavailable`() {
        writeWeb2mYml(bankType = "ACB", password = "")
        assertSame(UnavailableBankProvider, resolve())
    }

    @Test
    fun `blank or placeholder token is unavailable`() {
        writeWeb2mYml(token = "")
        assertSame(UnavailableBankProvider, resolve())
        writeWeb2mYml(token = "YOUR_WEB2M_TOKEN")
        assertSame(UnavailableBankProvider, resolve())
    }

    @Test
    fun `blank or placeholder account is unavailable`() {
        writeWeb2mYml(account = "")
        assertSame(UnavailableBankProvider, resolve())
        writeWeb2mYml(account = "YOUR_BANK_ACCOUNT_NUMBER")
        assertSame(UnavailableBankProvider, resolve())
    }

    @Test
    fun `an invalid bank-type is unavailable`() {
        writeWeb2mYml(bankType = "NOTABANK")
        assertSame(UnavailableBankProvider, resolve())
    }

    @Test
    fun `a missing provider config is unavailable`() {
        // no web2m.yml written
        val registry = BankProviderRegistry(logger, BankProviderRegistry.defaultFactory(http, dataFolder, logger))
        assertSame(UnavailableBankProvider, registry.resolve(Web2MBankProvider.NAME))
    }

    @Test
    fun `an omitted webhook-auth defaults to blank so the webhook rejects instead of accepting all`() {
        val dir = File(dataFolder, "providers").apply { mkdirs() }
        // No webhook-auth key at all — the fail-open default would coerce this to "none" and accept anything.
        File(dir, "web2m.yml").writeText(
            """
            account-number: "0123456789"
            bank-type: "ACB"
            password: "secret"
            token: "tok123"
            webhook-token: "whtok"
            """.trimIndent(),
        )
        val provider = resolve()
        assertTrue(provider is BankWebhookCapable)
        val handler = (provider as BankWebhookCapable).webhookHandler(
            BankWebhookDeps(
                findPendingByContainedReference = { _, _ -> null },
                confirm = { throw AssertionError("an unauthenticated webhook must never confirm") },
                logger = logger,
            ),
        )
        val response = handler.handle(
            WebhookRequest(
                method = "POST",
                path = "/kmd/web2m",
                query = emptyMap(),
                headers = emptyMap(),
                rawBody = """{"status":true,"data":[{"type":"IN","transactionID":"1","amount":"50000",""".toByteArray(),
            ),
        )
        assertEquals(401, response.status)
    }

    @Test
    fun `a default web2m yml ships on the classpath`() {
        val shipped = javaClass.classLoader.getResource("providers/web2m.yml")
        assertNotNull(shipped, "providers/web2m.yml must be shipped")
    }
}
