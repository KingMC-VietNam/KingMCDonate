package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class Card2kCardProviderTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private val request = CardRequest(UUID.randomUUID(), CardType.VIETTEL, 10_000, "seri", "pin", "REF1")

    private fun provider(response: String) =
        Card2kCardProvider({ _, _ -> response }, "pid", "pkey", false, setOf(CardType.VIETTEL), logger)

    @Test
    fun `pending status maps to WAITING`() {
        val outcome = provider("""{"status":99,"message":"pending"}""").submit(request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `success status surfaces recognized value`() {
        val outcome = provider("""{"status":1,"value":"10000","message":"ok"}""").submit(request)
        assertEquals(PaymentStatus.SUCCESS, outcome.status)
        assertEquals(10_000L, outcome.recognizedAmount)
    }

    @Test
    fun `success with mismatched value is surfaced for the payment layer to reject`() {
        val outcome = provider("""{"status":1,"value":"5000","message":"ok"}""").submit(request)
        assertEquals(PaymentStatus.SUCCESS, outcome.status)
        assertEquals(5_000L, outcome.recognizedAmount)
    }

    @Test
    fun `failed status maps to FAILED`() {
        val outcome = provider("""{"status":3,"message":"that bai"}""").submit(request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }

    @Test
    fun `maintenance status maps to FAILED`() {
        val outcome = provider("""{"status":4,"message":"bao tri"}""").check("REF1", request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }
}
