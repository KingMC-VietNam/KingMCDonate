package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class SuccessBroadcasterTest {

    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private fun donation(name: String?) =
        Donation(uuid, name, "card", 100_000, 1000, "REF1", MessageKeys.CARD_SUCCESS, "card2k")

    @Test
    fun `renders every token and colorizes`() {
        val out = SuccessBroadcaster.render("&a{player} nap {amount} (&b{point}pt&a) qua {method}", donation("Alice"))
        assertEquals("§aAlice nap 100.000đ (§b1000pt§a) qua card", out)
    }

    @Test
    fun `falls back to uuid when name is null`() {
        val out = SuccessBroadcaster.render("{player}", donation(null))
        assertEquals(uuid.toString(), out)
    }
}
