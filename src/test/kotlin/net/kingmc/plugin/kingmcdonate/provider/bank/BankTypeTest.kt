package net.kingmc.plugin.kingmcdonate.provider.bank

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BankTypeTest {

    @Test
    fun `parse resolves a known bank case-insensitively and trims`() {
        assertEquals(BankType.ACB, BankType.parse("ACB"))
        assertEquals(BankType.ACB, BankType.parse("acb"))
        assertEquals(BankType.ACB, BankType.parse("  Acb  "))
    }

    @Test
    fun `parse returns null for unknown or blank`() {
        assertNull(BankType.parse("NOPE"))
        assertNull(BankType.parse(""))
        assertNull(BankType.parse("   "))
    }

    @Test
    fun `enum carries the V3 bin and path`() {
        assertEquals("970416", BankType.ACB.bin)
        assertEquals("historyapiacbv3", BankType.ACB.web2mPath)
        assertEquals("970436", BankType.VCB.bin)
        assertEquals("historyapivcbv3", BankType.VCB.web2mPath)
    }

    @Test
    fun `open-api banks are one-param, the rest are not`() {
        assertTrue(BankType.BIDV_OPENAPI.oneParam)
        assertTrue(BankType.MBBANK_OPENAPI.oneParam)
        assertFalse(BankType.ACB.oneParam)
        assertFalse(BankType.TECHCOMBANK.oneParam)
    }

    @Test
    fun `all nine V3 banks are present`() {
        assertEquals(9, BankType.entries.size)
    }
}
