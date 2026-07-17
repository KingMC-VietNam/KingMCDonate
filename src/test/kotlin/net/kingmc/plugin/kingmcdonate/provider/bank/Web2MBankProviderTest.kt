package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class Web2MBankProviderTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    private fun provider(
        response: String = "",
        bankType: BankType = BankType.ACB,
        capture: ((String) -> Unit)? = null,
    ) = Web2MBankProvider(
        httpGet = { url, _ -> capture?.invoke(url); response },
        accountNumber = "0123456789",
        bankType = bankType,
        password = "secret",
        token = "tok123",
        accountHolder = "Nguyen Van A",
        logger = logger,
    )

    private fun order(reference: String, amount: Long) = BankPayment(
        id = 1, playerUuid = UUID.randomUUID(), amount = amount, referenceCode = reference,
        status = PaymentStatus.PENDING, provider = "web2m", ownerServer = "node-a", externalRef = null,
        point = 0, rewardApplied = false, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `createQr uses qr sepay img with the bank-type BIN`() {
        val qr = provider().createQr(50_000, "UNG HO TTA1B2C3D4")
        assertTrue(qr.imageUrl.startsWith("https://qr.sepay.vn/img?"))
        assertTrue(qr.imageUrl.contains("acc=0123456789"))
        assertTrue(qr.imageUrl.contains("bank=970416")) // ACB BIN
        assertTrue(qr.imageUrl.contains("amount=50000"))
        assertTrue(qr.imageUrl.contains("des=UNG+HO+TTA1B2C3D4"))
        assertTrue(qr.imageUrl.contains("template=qronly"))
        assertEquals("Nguyen Van A", qr.accountHolder)
    }

    @Test
    fun `poll for a non-one-param bank calls password-account-token endpoint`() {
        var url = ""
        provider(bankType = BankType.ACB, capture = { url = it }).poll(listOf(order("KMD7X9A2QP", 50_000)))
        assertEquals("https://api.web2m.com/historyapiacbv3/secret/0123456789/tok123", url)
    }

    @Test
    fun `poll for a one-param bank calls token-only endpoint`() {
        var url = ""
        provider(bankType = BankType.MBBANK_OPENAPI, capture = { url = it }).poll(listOf(order("KMD7X9A2QP", 50_000)))
        assertEquals("https://api.web2m.com/historyapiopenmbv3/tok123", url)
    }

    @Test
    fun `poll parses the envelope and matches an IN transfer by contained ref and amount`() {
        val json = """
            {
              "status": true,
              "message": "ok",
              "transactions": [
                { "transactionID": "9668", "amount": "150000",
                  "description": "CK KMD7X9A2QP NAP", "transactionDate": "2025-02-20 14:15:00", "type": "IN" }
              ]
            }
        """.trimIndent()
        val matches = provider(json).poll(listOf(order("KMD7X9A2QP", 150_000))).confirmations
        assertEquals(1, matches.size)
        assertEquals("KMD7X9A2QP", matches.first().referenceCode)
        assertEquals("9668", matches.first().transactionId)
        assertEquals(150_000L, matches.first().amount)
    }

    @Test
    fun `poll reads amount and transactionID given as JSON numbers`() {
        val json = """
            { "status": true, "transactions": [
                { "transactionID": 9668, "amount": 150000, "description": "thanh toan KMD7X9A2QP", "type": "IN" } ] }
        """.trimIndent()
        val matches = provider(json).poll(listOf(order("KMD7X9A2QP", 150_000))).confirmations
        assertEquals(1, matches.size)
        assertEquals("9668", matches.first().transactionId)
    }

    @Test
    fun `poll matches a ref glued to surrounding content`() {
        val json = """
            { "status": true, "transactions": [
                { "transactionID": "7", "amount": "50000", "description": "UNG HO TTA1B2C3D4", "type": "IN" } ] }
        """.trimIndent()
        val matches = provider(json).poll(listOf(order("A1B2C3D4", 50_000))).confirmations
        assertEquals(1, matches.size)
        assertEquals("A1B2C3D4", matches.first().referenceCode)
    }

    @Test
    fun `poll ignores OUT transfers even with a matching ref`() {
        val json = """
            { "status": true, "transactions": [
                { "transactionID": "7", "amount": "-1000000", "description": "KMD7X9A2QP", "type": "OUT" } ] }
        """.trimIndent()
        assertTrue(provider(json).poll(listOf(order("KMD7X9A2QP", 1_000_000))).confirmations.isEmpty())
    }

    @Test
    fun `poll does not match when the amount differs`() {
        val json = """
            { "status": true, "transactions": [
                { "transactionID": "7", "amount": "20000", "description": "KMD7X9A2QP", "type": "IN" } ] }
        """.trimIndent()
        assertTrue(provider(json).poll(listOf(order("KMD7X9A2QP", 50_000))).confirmations.isEmpty())
    }

    @Test
    fun `poll returns empty and makes no call when there are no orders`() {
        var called = false
        provider(capture = { called = true }).poll(emptyList())
        assertTrue(!called)
    }
}
