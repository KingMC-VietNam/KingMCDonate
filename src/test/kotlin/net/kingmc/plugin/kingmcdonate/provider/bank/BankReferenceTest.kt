package net.kingmc.plugin.kingmcdonate.provider.bank

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BankReferenceTest {

    @Test
    fun `joins code and content and uppercases`() {
        val hay = BankReference.searchText("kmd7x9a2qp", "ck other nap")
        assertTrue(hay.contains("KMD7X9A2QP"))
        assertTrue(hay.contains("CK OTHER NAP"))
    }

    @Test
    fun `code and content are separated so a reference cannot bridge the boundary`() {
        // code ends "AAAA", content starts "BBBB"; the joined text must not contain "AAAABBBB".
        assertFalse(BankReference.searchText("AAAA", "BBBB").contains("AAAABBBB"))
    }

    @Test
    fun `null parts are treated as empty`() {
        assertTrue(BankReference.searchText(null, "CK A1B2C3D4 NAP").contains("A1B2C3D4"))
        assertTrue(BankReference.searchText("A1B2C3D4", null).contains("A1B2C3D4"))
        assertFalse(BankReference.searchText(null, null).contains("A1B2C3D4"))
    }

    @Test
    fun `contains a reference glued to surrounding content`() {
        // The bank app dropped the space between the prefix and the ref: "UNG HO TT" + "A1B2C3D4".
        assertTrue(BankReference.searchText(null, "UNG HO TTA1B2C3D4").contains("A1B2C3D4"))
    }
}
