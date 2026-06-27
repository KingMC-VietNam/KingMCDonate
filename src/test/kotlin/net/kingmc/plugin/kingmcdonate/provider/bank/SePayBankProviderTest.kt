package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.payment.model.BankPayment
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class SePayBankProviderTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    private fun provider(response: String = "") = SePayBankProvider(
        httpGet = { _, _ -> response },
        accountNumber = "0123456789",
        bank = "ACB",
        apiToken = "token",
        sandbox = false,
        logger = logger,
    )

    private fun order(reference: String, amount: Long) = BankPayment(
        id = 1,
        playerUuid = UUID.randomUUID(),
        amount = amount,
        referenceCode = reference,
        status = PaymentStatus.PENDING,
        provider = "sepay",
        ownerServer = "node-a",
        externalRef = null,
        point = 0,
        rewardApplied = false,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun tx(
        id: String,
        transferType: String = "in",
        amountIn: Long = 50_000,
        content: String? = null,
        code: String? = null,
    ) = SePayBankProvider.SePayTransaction(
        id = id,
        transferType = transferType,
        amountIn = amountIn,
        content = content,
        code = code,
    )

    @Test
    fun `createQr encodes account bank amount and reference`() {
        val qr = provider().createQr(50_000, "KMD7X9A2QP")
        assertTrue(qr.imageUrl.startsWith("https://qr.sepay.vn/img?"))
        assertTrue(qr.imageUrl.contains("acc=0123456789"))
        assertTrue(qr.imageUrl.contains("bank=ACB"))
        assertTrue(qr.imageUrl.contains("amount=50000"))
        assertTrue(qr.imageUrl.contains("des=KMD7X9A2QP"))
        assertTrue(qr.imageUrl.contains("template=qronly"))
    }

    @Test
    fun `exact token in content matches`() {
        val orders = listOf(order("KMD7X9A2QP", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", content = "CK KMD7X9A2QP MUA POINT")))
        assertEquals(1, matches.size)
        assertEquals("KMD7X9A2QP", matches.first().referenceCode)
        assertEquals("T1", matches.first().transactionId)
    }

    @Test
    fun `prefixed reference matches whole via code field and content token`() {
        val orders = listOf(order("KMDK8V2PK2WD9", 50_000))
        val byCode = provider().match(orders, listOf(tx("T1", content = "noise", code = "KMDK8V2PK2WD9")))
        val byContent = provider().match(orders, listOf(tx("T2", content = "CK KMDK8V2PK2WD9 NAP")))
        assertEquals("KMDK8V2PK2WD9", byCode.first().referenceCode)
        assertEquals("KMDK8V2PK2WD9", byContent.first().referenceCode)
    }

    @Test
    fun `extracted code matches when present`() {
        val orders = listOf(order("KMD7X9A2QP", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", content = "noise", code = "KMD7X9A2QP")))
        assertEquals(1, matches.size)
    }

    @Test
    fun `substring of a longer token does not match`() {
        val orders = listOf(order("KMD1", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", content = "CK KMD12 abc")))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `outgoing transfer is ignored`() {
        val orders = listOf(order("KMD7X9A2QP", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", transferType = "out", content = "KMD7X9A2QP")))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `amount mismatch is not matched`() {
        val orders = listOf(order("KMD7X9A2QP", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", amountIn = 20_000, content = "KMD7X9A2QP")))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `poll requests a date-bounded page with the documented parameters`() {
        var calledUrl = ""
        val provider = SePayBankProvider(
            httpGet = { url, _ -> calledUrl = url; "" },
            accountNumber = "0123456789", bank = "ACB", apiToken = "token", sandbox = false, logger = logger,
        )
        provider.poll(listOf(order("KMD7X9A2QP", 50_000)))
        assertTrue(calledUrl.contains("account_number=0123456789"))
        assertTrue(calledUrl.contains("per_page=100"))
        assertTrue(calledUrl.contains("transaction_date_from="))
    }

    @Test
    fun `poll parses the v2 envelope and matches`() {
        val json = """
            {
              "status": "success",
              "data": [
                {
                  "id": "a1b2c3d4",
                  "transaction_date": "2025-02-20 14:15:00",
                  "transfer_type": "in",
                  "amount_in": 50000,
                  "amount_out": 0,
                  "transaction_content": "thanh toan KMD7X9A2QP",
                  "reference_number": "FT001",
                  "code": null
                }
              ],
              "meta": { "pagination": { "total": 1 } }
            }
        """.trimIndent()
        val matches = provider(json).poll(listOf(order("KMD7X9A2QP", 50_000)))
        assertEquals(1, matches.size)
        assertEquals("a1b2c3d4", matches.first().transactionId)
    }
}
