package net.kingmc.plugin.kingmcdonate.command

import net.kingmc.plugin.kingmcdonate.command.GiveSubCommand.GiveParse
import net.kingmc.plugin.kingmcdonate.payment.ManualCreditService.Bucket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GiveSubCommandTest {

    private fun ok(vararg args: String) = GiveSubCommand.parse(args.toList()) as GiveParse.Ok

    @Test
    fun `parses bank bucket with defaults`() {
        val r = ok("bank", "Alice", "50000")
        assertEquals(Bucket.BANK, r.bucket)
        assertEquals("Alice", r.name)
        assertEquals(50000L, r.amount)
        assertEquals(null, r.point)
        assertEquals(false, r.force)
    }

    @Test
    fun `parses card bucket with explicit point`() {
        val r = ok("card", "Bob", "137000", "500")
        assertEquals(Bucket.CARD, r.bucket)
        assertEquals(500L, r.point)
    }

    @Test
    fun `force flag is recognized anywhere`() {
        assertEquals(true, ok("bank", "Bob", "50000", "-f").force)
        assertEquals(true, ok("bank", "-f", "Bob", "50000").force)
        // -f alongside an explicit point still parses both.
        val r = ok("bank", "Bob", "50000", "500", "-f")
        assertEquals(500L, r.point)
        assertEquals(true, r.force)
    }

    @Test
    fun `invalid bucket is reported distinctly`() {
        assertEquals(GiveParse.InvalidBucket, GiveSubCommand.parse(listOf("wallet", "Bob", "50000")))
    }

    @Test
    fun `malformed input falls back to usage`() {
        assertEquals(GiveParse.Usage, GiveSubCommand.parse(listOf("bank", "Bob")))                 // too few
        assertEquals(GiveParse.Usage, GiveSubCommand.parse(listOf("bank", "Bob", "50000", "5", "x"))) // too many
        assertEquals(GiveParse.Usage, GiveSubCommand.parse(listOf("bank", "Bob", "abc")))           // non-numeric amount
        assertEquals(GiveParse.Usage, GiveSubCommand.parse(listOf("bank", "Bob", "0")))             // non-positive amount
        assertEquals(GiveParse.Usage, GiveSubCommand.parse(listOf("bank", "Bob", "50000", "-5")))   // negative point
    }
}
