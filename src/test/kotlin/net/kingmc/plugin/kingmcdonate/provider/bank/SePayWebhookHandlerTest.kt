package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.webhook.BankWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SePayWebhookHandlerTest {

    private val logger = PluginLogger(java.util.logging.Logger.getAnonymousLogger(), debugMode = false)
    private val secret = "whsec"

    private val order = BankPayment(
        id = 1, playerUuid = UUID.randomUUID(), amount = 50_000, referenceCode = "KMD7X9A2QP",
        status = PaymentStatus.PENDING, provider = "sepay", ownerServer = "node-a", externalRef = null,
        rewardApplied = false, createdAt = 0, updatedAt = 0,
    )

    private var confirmed: net.kingmc.plugin.kingmcdonate.provider.bank.BankConfirmation? = null

    private fun deps(found: BankPayment? = order) = BankWebhookDeps(
        findByReference = { if (it == order.referenceCode) found else null },
        confirm = { confirmed = it },
        logger = logger,
    )

    private fun body(
        id: Long = 92704,
        transferType: String = "in",
        amount: Long = 50_000,
        content: String = "CK KMD7X9A2QP NAP",
        code: String? = null,
    ): String {
        val codeJson = if (code == null) "null" else "\"$code\""
        return """{"id":$id,"transferType":"$transferType","transferAmount":$amount,""" +
            """"content":"$content","code":$codeJson}"""
    }

    private fun hmacRequest(rawBody: String, ts: Long = System.currentTimeMillis() / 1000, sig: String? = null): WebhookRequest {
        val signature = sig ?: ("sha256=" + Hashing.hmacSha256Hex(secret, "$ts.$rawBody"))
        return WebhookRequest(
            method = "POST",
            path = "/kmd/sepay",
            query = emptyMap(),
            headers = mapOf("X-SePay-Signature" to signature, "X-SePay-Timestamp" to ts.toString()),
            rawBody = rawBody.toByteArray(),
        )
    }

    private fun handler(auth: String, apiKey: String = "") =
        SePayWebhookHandler(auth, secret, apiKey, deps())

    @Test
    fun `valid hmac matching transfer confirms once with success body`() {
        val raw = body()
        val response = handler("hmac").handle(hmacRequest(raw))
        assertEquals(200, response.status)
        assertEquals("""{"success":true}""", response.body)
        assertEquals("KMD7X9A2QP", confirmed?.referenceCode)
        assertEquals("92704", confirmed?.transactionId)
        assertEquals(50_000L, confirmed?.amount)
    }

    @Test
    fun `extracted code field is used for matching`() {
        val raw = body(content = "noise", code = "KMD7X9A2QP")
        handler("hmac").handle(hmacRequest(raw))
        assertEquals("KMD7X9A2QP", confirmed?.referenceCode)
    }

    @Test
    fun `prefers the candidate whose order amount matches the transfer`() {
        val stale = order.copy(referenceCode = "KMDSTALE01", amount = 99_000)
        val correct = order.copy(referenceCode = "KMD7X9A2QP", amount = 50_000)
        val deps = BankWebhookDeps(
            findByReference = { ref ->
                when (ref) {
                    "KMDSTALE01" -> stale
                    "KMD7X9A2QP" -> correct
                    else -> null
                }
            },
            confirm = { confirmed = it },
            logger = logger,
        )
        // The stale token appears first in the content, but only the second matches the amount.
        val raw = body(amount = 50_000, content = "CK KMDSTALE01 KMD7X9A2QP NAP")
        SePayWebhookHandler("hmac", secret, "", deps).handle(hmacRequest(raw))
        assertEquals("KMD7X9A2QP", confirmed?.referenceCode)
    }

    @Test
    fun `stale timestamp is rejected`() {
        val raw = body()
        val staleTs = System.currentTimeMillis() / 1000 - 1000
        val response = handler("hmac").handle(hmacRequest(raw, ts = staleTs))
        assertEquals(401, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `bad signature is rejected`() {
        val response = handler("hmac").handle(hmacRequest(body(), sig = "sha256=deadbeef"))
        assertEquals(401, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `apikey scheme accepts the configured key and rejects others`() {
        val raw = body()
        val ok = SePayWebhookHandler("apikey", secret, "K", deps()).handle(
            WebhookRequest("POST", "/kmd/sepay", emptyMap(), mapOf("Authorization" to "Apikey K"), raw.toByteArray()),
        )
        assertEquals(200, ok.status)

        confirmed = null
        val bad = SePayWebhookHandler("apikey", secret, "K", deps()).handle(
            WebhookRequest("POST", "/kmd/sepay", emptyMap(), mapOf("Authorization" to "Apikey WRONG"), raw.toByteArray()),
        )
        assertEquals(401, bad.status)
        assertNull(confirmed)
    }

    @Test
    fun `authentic but unmatched transfer is acknowledged without confirming`() {
        val handler = SePayWebhookHandler("hmac", secret, "", deps(found = null))
        val response = handler.handle(hmacRequest(body()))
        assertEquals(200, response.status)
        assertEquals("""{"success":true}""", response.body)
        assertNull(confirmed)
    }

    @Test
    fun `outgoing transfer is acknowledged but not confirmed`() {
        val response = handler("hmac").handle(hmacRequest(body(transferType = "out")))
        assertEquals(200, response.status)
        assertNull(confirmed)
    }

    @Test
    fun `none scheme accepts without verification`() {
        val response = SePayWebhookHandler("none", "", "", deps()).handle(
            WebhookRequest("POST", "/kmd/sepay", emptyMap(), emptyMap(), body().toByteArray()),
        )
        assertEquals(200, response.status)
        assertTrue(confirmed != null)
    }
}
