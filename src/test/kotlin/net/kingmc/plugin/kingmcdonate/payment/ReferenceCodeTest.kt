package net.kingmc.plugin.kingmcdonate.payment

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceCodeTest {

    @Test
    fun `generated code is ten upper-case alphanumerics`() {
        repeat(100) {
            assertTrue(ReferenceCode.generate().matches(Regex("[A-Z0-9]{10}")))
        }
    }

    @Test
    fun `successive codes differ`() {
        val codes = (1..100).map { ReferenceCode.generate() }.toSet()
        assertTrue(codes.size > 90, "expected mostly unique codes, got ${codes.size}")
    }
}
