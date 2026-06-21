package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class ThesieutocCardProviderTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private val request = CardRequest(UUID.randomUUID(), CardType.VIETTEL, 10_000, "seri", "pin", "REF1")

    private fun provider(response: String) =
        ThesieutocCardProvider({ response }, "key", "secret", setOf(CardType.VIETTEL), logger)

    @Test
    fun `submit accepted with transaction id maps to WAITING`() {
        val outcome = provider("""{"status":"00","msg":"ok","transaction_id":"T1"}""").submit(request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
        assertEquals("T1", outcome.transactionId)
    }

    @Test
    fun `submit rejected status maps to FAILED`() {
        val outcome = provider("""{"status":"2","msg":"loi"}""").submit(request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }

    @Test
    fun `submit with unsupported denomination fails without calling the gateway`() {
        val provider = ThesieutocCardProvider(
            { throw AssertionError("gateway should not be called") },
            "key", "secret", setOf(CardType.VIETTEL), logger,
        )
        val outcome = provider.submit(request.copy(declaredAmount = 99_999))
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }

    @Test
    fun `poll pending maps to WAITING`() {
        val outcome = provider("""{"status":"-9","msg":"dang xu ly"}""").check("T1", request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `poll success requires status and success message`() {
        val outcome = provider("""{"status":"00","msg":"Nạp thành công"}""").check("T1", request)
        assertEquals(PaymentStatus.SUCCESS, outcome.status)
    }

    @Test
    fun `poll wrong denomination maps to FAILED`() {
        val outcome = provider("""{"status":"10","msg":"sai menh gia"}""").check("T1", request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }

    @Test
    fun `poll failed maps to FAILED`() {
        val outcome = provider("""{"status":"-10","msg":"that bai"}""").check("T1", request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }
}
