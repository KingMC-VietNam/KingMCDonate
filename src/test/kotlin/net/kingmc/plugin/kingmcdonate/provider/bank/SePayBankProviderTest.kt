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
    fun `reference in content matches`() {
        val orders = listOf(order("A1B2C3D4", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", content = "CK A1B2C3D4 MUA POINT")))
        assertEquals(1, matches.size)
        assertEquals("A1B2C3D4", matches.first().referenceCode)
        assertEquals("T1", matches.first().transactionId)
    }

    @Test
    fun `reference in code field or content both match`() {
        val orders = listOf(order("A1B2C3D4", 50_000))
        val byCode = provider().match(orders, listOf(tx("T1", content = "noise", code = "A1B2C3D4")))
        val byContent = provider().match(orders, listOf(tx("T2", content = "CK A1B2C3D4 NAP")))
        assertEquals("A1B2C3D4", byCode.first().referenceCode)
        assertEquals("A1B2C3D4", byContent.first().referenceCode)
    }

    @Test
    fun `a different order reference is not matched`() {
        val orders = listOf(order("A1B2C3D4", 50_000), order("E5F6G7H8", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", content = "CK A1B2C3D4 NAP")))
        assertEquals(1, matches.size)
        assertEquals("A1B2C3D4", matches.first().referenceCode)
    }

    @Test
    fun `reference matches even when the bank strips the space before it`() {
        // Player content "UNG HO TT" + "A1B2C3D4"; the bank app dropped the space between them.
        val orders = listOf(order("A1B2C3D4", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", content = "UNG HO TTA1B2C3D4")))
        assertEquals(1, matches.size)
        assertEquals("A1B2C3D4", matches.first().referenceCode)
    }

    @Test
    fun `outgoing transfer is ignored`() {
        val orders = listOf(order("A1B2C3D4", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", transferType = "out", content = "A1B2C3D4")))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `amount mismatch is not matched`() {
        val orders = listOf(order("A1B2C3D4", 50_000))
        val matches = provider().match(orders, listOf(tx("T1", amountIn = 20_000, content = "A1B2C3D4")))
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
