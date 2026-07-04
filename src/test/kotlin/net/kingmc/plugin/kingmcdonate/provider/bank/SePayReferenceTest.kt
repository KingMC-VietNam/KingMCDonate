package net.kingmc.plugin.kingmcdonate.provider.bank

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SePayReferenceTest {

    @Test
    fun `extracted code takes priority and is uppercased and trimmed`() {
        assertEquals(listOf("KMD7X9A2QP"), SePayReference.candidates("  kmd7x9a2qp ", "CK OTHER NAP"))
    }

    @Test
    fun `falls back to content tokens when code is null or blank`() {
        assertEquals(listOf("CK", "KMD7X9A2QP", "NAP"), SePayReference.candidates(null, "CK KMD7X9A2QP NAP"))
        assertEquals(listOf("CK", "KMD7X9A2QP", "NAP"), SePayReference.candidates("   ", "CK KMD7X9A2QP NAP"))
    }

    @Test
    fun `content is split on non-alphanumeric runs and uppercased`() {
        assertEquals(listOf("CK", "KMD7ABC", "NAP"), SePayReference.candidates(null, "ck-kmd7abc.nap"))
    }

    @Test
    fun `a short reference does not match inside a longer token`() {
        val candidates = SePayReference.candidates(null, "CK KMD7ABC NAP")
        assertTrue("KMD7ABC" in candidates)
        assertFalse("KMD7" in candidates, "short ref must not match as a substring of a longer token")
    }

    @Test
    fun `null content yields no candidates`() {
        assertEquals(emptyList<String>(), SePayReference.candidates(null, null))
    }
}
