package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class Web2MWebhookHandlerTest {

    private val logger = PluginLogger(java.util.logging.Logger.getAnonymousLogger(), debugMode = false)

    private val order = BankPayment(
        id = 1, playerUuid = UUID.randomUUID(), amount = 50_000, referenceCode = "KMD7X9A2QP",
        status = PaymentStatus.PENDING, provider = "web2m", ownerServer = "node-a", externalRef = null,
        point = 0, rewardApplied = false, createdAt = 0, updatedAt = 0,
    )

    private var confirmed: BankConfirmation? = null

    private fun deps(found: BankPayment? = order, log: PluginLogger = logger) = BankWebhookDeps(
        findPendingByContainedReference = { haystack, amount ->
            found?.takeIf { haystack.contains(it.referenceCode) && amount == it.amount }
        },
        confirm = { confirmed = it },
        logger = log,
    )

    private fun item(
        id: String = "notif-1",
        type: String = "IN",
        transactionID: String = "9668",
        amount: String = "50000",
        description: String = "CK KMD7X9A2QP NAP",
    ) = """{"id":"$id","type":"$type","transactionID":"$transactionID","amount":"$amount",""" +
        """"description":"$description","date":"2025-02-20","bank":"ACB"}"""

    private fun body(vararg items: String) = """{"status":true,"data":[${items.joinToString(",")}]}"""

    private fun request(raw: String, authHeader: String? = "Bearer whtok") = WebhookRequest(
        method = "POST",
        path = "/kmd/web2m",
        query = emptyMap(),
        headers = if (authHeader == null) emptyMap() else mapOf("Authorization" to authHeader),
        rawBody = raw.toByteArray(),
    )

    private fun handler(auth: String = "bearer", token: String = "whtok", d: BankWebhookDeps = deps()) =
        Web2MWebhookHandler(auth, token, d)

    @Test
    fun `valid bearer matching IN transfer confirms once with the Web2M ACK body`() {
        val response = handler().handle(request(body(item())))
        assertEquals(200, response.status)
        assertEquals("""{"status":true,"msg":"Ok"}""", response.body)
        assertEquals("KMD7X9A2QP", confirmed?.referenceCode)
        assertEquals(50_000L, confirmed?.amount)
    }

    @Test
    fun `dedup key is transactionID not the notification id`() {
        handler().handle(request(body(item(id = "notif-999", transactionID = "9668"))))
        assertEquals("9668", confirmed?.transactionId)
    }

    @Test
    fun `wrong bearer token is rejected with 401 and no confirm`() {
        val response = handler().handle(request(body(item()), authHeader = "Bearer WRONG"))
        assertEquals(401, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `missing authorization header is rejected with 401`() {
        val response = handler().handle(request(body(item()), authHeader = null))
        assertEquals(401, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `none scheme accepts without a token`() {
        val response = handler(auth = "none", token = "").handle(request(body(item()), authHeader = null))
        assertEquals(200, response.status)
        assertEquals("KMD7X9A2QP", confirmed?.referenceCode)
    }

    @Test
    fun `a blank auth scheme rejects with 401 and never confirms`() {
        val response = handler(auth = "").handle(request(body(item())))
        assertEquals(401, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `a blank auth scheme warns at construction that requests will be rejected`() {
        val capturing = CapturingLogger()
        Web2MWebhookHandler("", "whtok", deps(log = capturing.plugin))
        assertTrue(
            capturing.warnedContaining("web2m", "rejected"),
            "expected a startup warning naming the provider and the rejection; got ${capturing.warnings}",
        )
    }

    @Test
    fun `the none scheme warns at construction that authentication is disabled`() {
        val capturing = CapturingLogger()
        Web2MWebhookHandler("none", "", deps(log = capturing.plugin))
        assertTrue(
            capturing.warnedContaining("web2m", "disabled"),
            "expected a startup warning that auth is disabled; got ${capturing.warnings}",
        )
    }

    @Test
    fun `a configured scheme warns nothing at construction`() {
        val capturing = CapturingLogger()
        Web2MWebhookHandler("bearer", "whtok", deps(log = capturing.plugin))
        assertTrue(capturing.warnings.isEmpty(), "a configured scheme must be silent; got ${capturing.warnings}")
    }

    @Test
    fun `OUT item is acknowledged but not confirmed`() {
        val response = handler().handle(request(body(item(type = "OUT", amount = "-50000"))))
        assertEquals(200, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `authentic transfer matching no order is acknowledged without confirming`() {
        val response = handler(d = deps(found = null)).handle(request(body(item())))
        assertEquals(200, response.status)
        assertEquals("""{"status":true,"msg":"Ok"}""", response.body)
        assertNull(confirmed)
    }

    @Test
    fun `empty data is acknowledged`() {
        val response = handler().handle(request("""{"status":true,"data":[]}"""))
        assertEquals(200, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `the amount selects the order among several references in one batch`() {
        val stale = order.copy(referenceCode = "KMDSTALE01", amount = 99_000)
        val correct = order.copy(referenceCode = "KMD7X9A2QP", amount = 50_000)
        val d = BankWebhookDeps(
            findPendingByContainedReference = { haystack, amount ->
                listOf(stale, correct).firstOrNull { haystack.contains(it.referenceCode) && amount == it.amount }
            },
            confirm = { confirmed = it },
            logger = logger,
        )
        handler(d = d).handle(request(body(item(amount = "50000", description = "CK KMDSTALE01 KMD7X9A2QP NAP"))))
        assertEquals("KMD7X9A2QP", confirmed?.referenceCode)
    }
}
