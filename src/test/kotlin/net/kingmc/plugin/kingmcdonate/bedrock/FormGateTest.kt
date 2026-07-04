package net.kingmc.plugin.kingmcdonate.bedrock

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormGateTest {

    @Test
    fun `uses form only when all four conditions hold`() {
        assertTrue(FormGate.shouldUse(masterEnabled = true, formEnabled = true, floodgatePresent = true, isBedrock = true))
    }

    @Test
    fun `master toggle off blocks the form`() {
        assertFalse(FormGate.shouldUse(masterEnabled = false, formEnabled = true, floodgatePresent = true, isBedrock = true))
    }

    @Test
    fun `per-form toggle off blocks the form`() {
        assertFalse(FormGate.shouldUse(masterEnabled = true, formEnabled = false, floodgatePresent = true, isBedrock = true))
    }

    @Test
    fun `floodgate absent blocks the form`() {
        assertFalse(FormGate.shouldUse(masterEnabled = true, formEnabled = true, floodgatePresent = false, isBedrock = true))
    }

    @Test
    fun `java player never gets the form`() {
        assertFalse(FormGate.shouldUse(masterEnabled = true, formEnabled = true, floodgatePresent = true, isBedrock = false))
    }
}
