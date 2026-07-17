package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.CardPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.provider.bank.CapturingLogger
import net.kingmc.plugin.kingmcdonate.util.Hashing
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import net.kingmc.plugin.kingmcdonate.webhook.CardWebhookDeps
import net.kingmc.plugin.kingmcdonate.webhook.WebhookRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class NencerCallbackHandlerTest {

    private val logger = PluginLogger(java.util.logging.Logger.getAnonymousLogger(), debugMode = false)
    private val partnerKey = "pkey"
    private val uuid = UUID.randomUUID()

    /** Holds the very card the callbacks below are signed for — the gateway only ever calls back about
     *  the card an order submitted, so a fixture where they differ describes traffic that cannot exist. */
    private val order = CardPayment(
        id = 1, playerUuid = uuid, playerName = "Alice", cardType = "VIETTEL", amount = 50_000,
        serial = "0898", pin = "664196324427", status = PaymentStatus.WAITING, referenceCode = "REF1",
        cardProvider = "card2k", transactionId = "T1", ownerServer = "node-a", point = 0,
        createdAt = 0, updatedAt = 0,
    )

    private class Applied(
        val ref: String, val uuid: UUID, val name: String?, val amount: Long, val outcome: CardOutcome,
    )

    private var applied: Applied? = null

    private fun handler(found: CardPayment? = order) = NencerCallbackHandler(
        "card2k",
        partnerKey,
        CardWebhookDeps(
            findByReference = { if (it == order.referenceCode) found else null },
            applyOutcome = { ref, u, n, amt, o -> applied = Applied(ref, u, n, amt, o) },
            logger = logger,
        ),
    )

    private fun sign(code: String, serial: String) = Hashing.md5Hex(partnerKey + code + serial)

    private fun request(status: String, code: String = "664196324427", serial: String = "0898", value: String = "50000", sign: String = sign(code, serial)) =
        WebhookRequest(
            method = "GET",
            path = "/kmd/card2k",
            query = mapOf(
                "status" to status, "request_id" to "REF1", "code" to code, "serial" to serial,
                "value" to value, "trans_id" to "343424", "message" to "ok", "callback_sign" to sign,
            ),
            headers = emptyMap(),
            rawBody = ByteArray(0),
        )

    @Test
    fun `providerKey is the active gateway name (route segment)`() {
        val deps = CardWebhookDeps({ null }, { _, _, _, _, _ -> }, logger)
        assertEquals("thesieure", NencerCallbackHandler("thesieure", partnerKey, deps).providerKey)
    }

    @Test
    fun `valid success callback resolves the order once and acks empty 200`() {
        val response = handler().handle(request("1"))
        assertEquals(200, response.status)
        assertEquals("", response.body)
        assertEquals(PaymentStatus.SUCCESS, applied?.outcome?.status)
        assertEquals(50_000L, applied?.outcome?.recognizedAmount)
        assertEquals("REF1", applied?.ref)
        assertEquals(50_000L, applied?.amount)
    }

    @Test
    fun `forged signature is rejected and applies nothing`() {
        val response = handler().handle(request("1", sign = "deadbeef"))
        assertEquals(401, response.status)
        assertNull(applied)
    }

    @Test
    fun `wrong-denomination status maps to FAILED and flags wrong denomination`() {
        handler().handle(request("2"))
        assertEquals(PaymentStatus.FAILED, applied?.outcome?.status)
        assertEquals(true, applied?.outcome?.wrongDenomination)
    }

    @Test
    fun `pending status maps to WAITING`() {
        handler().handle(request("99"))
        assertEquals(PaymentStatus.WAITING, applied?.outcome?.status)
    }

    @Test
    fun `maintenance status maps to WAITING`() {
        handler().handle(request("4"))
        assertEquals(PaymentStatus.WAITING, applied?.outcome?.status)
    }

    @Test
    fun `failed status maps to FAILED`() {
        handler().handle(request("3"))
        assertEquals(PaymentStatus.FAILED, applied?.outcome?.status)
    }

    @Test
    fun `used-card status maps to FAILED`() {
        handler().handle(request("100"))
        assertEquals(PaymentStatus.FAILED, applied?.outcome?.status)
    }

    @Test
    fun `unknown status stays WAITING so a charged card is not terminally failed`() {
        handler().handle(request("7"))
        assertEquals(PaymentStatus.WAITING, applied?.outcome?.status)
    }

    @Test
    fun `missing or non-numeric status stays WAITING`() {
        handler().handle(request("notanumber"))
        assertEquals(PaymentStatus.WAITING, applied?.outcome?.status)
    }

    @Test
    fun `unknown reference is acknowledged without applying`() {
        val response = handler(found = null).handle(request("1"))
        assertEquals(200, response.status)
        assertNull(applied)
    }

    @Test
    fun `a callback signed for another card cannot resolve this order`() {
        // C2. The gateway signs code+serial only — never request_id, status or value — so a valid
        // callback for any card the caller holds (one 10k card they bought is enough) carries a
        // signature that verifies while request_id points at someone else's order. Nothing but this
        // check stands between that and minting points at will.
        val response = handler().handle(request("1", code = "999999999999", serial = "1234"))

        assertEquals(401, response.status)
        assertNull(applied, "a card that does not belong to this order must never resolve it")
    }

    @Test
    fun `a callback echoing the card with extra padding still resolves`() {
        // We store the serial exactly as the player typed it; if the gateway trims before echoing,
        // the signature still verifies (it signs what it echoes) and only this compare would differ.
        // Rejecting here would strand a charged card in webhook-only mode, which runs no final check.
        val response = handler().handle(request("1", serial = " 0898 ", code = " 664196324427"))

        assertEquals(200, response.status)
        assertEquals(PaymentStatus.SUCCESS, applied?.outcome?.status)
    }

    @Test
    fun `a callback echoing a letter-bearing card in another case still resolves`() {
        val gate = order.copy(serial = "gate-abc", pin = "gate-pin")

        val response = handler(found = gate).handle(request("1", code = "GATE-PIN", serial = "GATE-ABC"))

        assertEquals(200, response.status)
        assertEquals(PaymentStatus.SUCCESS, applied?.outcome?.status)
    }

    @Test
    fun `a mismatch is logged at ERROR and never spells out the card`() {
        // A bad signature is internet noise; a *valid* signature aimed at the wrong order is either an
        // attack or a serious bug, so it must reach an operator — without printing card secrets to a log.
        val capturing = CapturingLogger()
        val h = NencerCallbackHandler(
            "card2k",
            partnerKey,
            CardWebhookDeps({ order }, { _, _, _, _, _ -> }, capturing.plugin),
        )

        h.handle(request("1", code = "999999999999", serial = "1234"))

        assertTrue(
            capturing.erroredContaining("card2k", "REF1"),
            "expected an ERROR naming the provider and the reference; got ${capturing.errors}",
        )
        assertTrue(
            capturing.errors.none { line ->
                listOf("999999999999", "1234", "0898", "664196324427").any { line.contains(it) }
            },
            "the log must not carry the card's serial or pin; got ${capturing.errors}",
        )
    }
}
