package net.kingmc.plugin.kingmcdonate.payment.bank

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BankTransferMessageTest {

    private val lines = listOf(
        "Bank: {bank}",
        "Account: {account}",
        "Holder: {holder}",
        "Amount: {amount}",
        "Content: {ref}",
    )

    @Test
    fun `substitutes all placeholders`() {
        val out = BankTransferMessage.build(lines, "MBBank", "0123456789", "100.000d", "KMD7ABC", "Nguyen Van A")
        assertEquals(
            listOf(
                "Bank: MBBank",
                "Account: 0123456789",
                "Holder: Nguyen Van A",
                "Amount: 100.000d",
                "Content: KMD7ABC",
            ),
            out,
        )
    }

    @Test
    fun `drops the holder line when holder is blank`() {
        val out = BankTransferMessage.build(lines, "MBBank", "0123456789", "100.000d", "KMD7ABC", "")
        assertFalse(out.any { it.contains("Holder") }, "blank holder line must be dropped")
        assertEquals(4, out.size)
    }

    @Test
    fun `treats a null holder as blank`() {
        val out = BankTransferMessage.build(lines, "MBBank", "0123456789", "100.000d", "KMD7ABC", null)
        assertFalse(out.any { it.contains("Holder") })
    }
}
