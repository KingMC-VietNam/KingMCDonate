package net.kingmc.plugin.kingmcdonate.provider.card

import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class NencerCardProviderTest {

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private val request = CardRequest(UUID.randomUUID(), CardType.VIETTEL, 10_000, "seri", "pin", "REF1")

    private fun provider(response: String) =
        NencerCardProvider("card2k", "https://card2k.net", { _, _, _ -> response }, "pid", "pkey", setOf(CardType.VIETTEL), logger)

    @Test
    fun `submit acknowledges intake as WAITING even when status is success`() {
        val outcome = provider("""{"status":1,"value":"10000","message":"ok"}""").submit(request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `submit pending maps to WAITING`() {
        val outcome = provider("""{"status":99,"message":"pending"}""").submit(request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `poll success surfaces recognized value`() {
        val outcome = provider("""{"status":1,"value":"10000","message":"ok"}""").check("REF1", request)
        assertEquals(PaymentStatus.SUCCESS, outcome.status)
        assertEquals(10_000L, outcome.recognizedAmount)
    }

    @Test
    fun `wrong-denomination status maps to FAILED and surfaces the recognized value`() {
        val outcome = provider("""{"status":2,"value":"5000","message":"sai menh gia"}""").check("REF1", request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
        assertEquals(5_000L, outcome.recognizedAmount)
    }

    @Test
    fun `failed status maps to FAILED`() {
        val outcome = provider("""{"status":3,"message":"that bai"}""").check("REF1", request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }

    @Test
    fun `used-card status maps to FAILED`() {
        val outcome = provider("""{"status":100,"message":"used"}""").check("REF1", request)
        assertEquals(PaymentStatus.FAILED, outcome.status)
    }

    @Test
    fun `unrecognized status maps to WAITING so the poll service retries`() {
        val outcome = provider("""{"status":7,"message":"busy"}""").check("REF1", request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `missing status maps to WAITING so the poll service retries`() {
        val outcome = provider("""{"message":"???"}""").check("REF1", request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `the configured base url is used for the charging endpoint`() {
        var calledUrl = ""
        val provider = NencerCardProvider(
            "thesieure", "https://thesieure.com/", // trailing slash should be trimmed
            { url, _, _ -> calledUrl = url; """{"status":99,"message":"pending"}""" },
            "pid", "pkey", setOf(CardType.VIETTEL), logger,
        )
        provider.submit(request)
        assertEquals("https://thesieure.com/chargingws/v2", calledUrl)
        assertEquals("thesieure", provider.name)
    }

    @Test
    fun `sandbox base url targets the card2k sandbox host`() {
        var calledUrl = ""
        val provider = NencerCardProvider(
            "card2k", NencerCardProvider.CARD2K_SANDBOX_BASE_URL,
            { url, _, _ -> calledUrl = url; """{"status":99,"message":"pending"}""" },
            "pid", "pkey", setOf(CardType.VIETTEL), logger,
        )
        provider.check("REF1", request)
        assertEquals("https://sandbox.card2k.net/chargingws/v2", calledUrl)
    }

    @Test
    fun `production base url targets the card2k production host`() {
        var calledUrl = ""
        val provider = NencerCardProvider(
            "card2k", NencerCardProvider.CARD2K_BASE_URL,
            { url, _, _ -> calledUrl = url; """{"status":99,"message":"pending"}""" },
            "pid", "pkey", setOf(CardType.VIETTEL), logger,
        )
        provider.check("REF1", request)
        assertEquals("https://card2k.net/chargingws/v2", calledUrl)
    }

    @Test
    fun `non-json body maps to WAITING without throwing`() {
        val outcome = provider("<html>maintenance</html>").check("REF1", request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `non-numeric status maps to WAITING without throwing`() {
        val outcome = provider("""{"status":"PENDING","message":"???"}""").check("REF1", request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `maintenance status maps to WAITING so the poll service retries`() {
        val outcome = provider("""{"status":4,"message":"bao tri"}""").check("REF1", request)
        assertEquals(PaymentStatus.WAITING, outcome.status)
    }

    @Test
    fun `charge is not retried but a status check is`() {
        var chargeRetry: Boolean? = null
        var checkRetry: Boolean? = null
        val provider = NencerCardProvider(
            "card2k", "https://card2k.net",
            { _, params, retry ->
                if (params["command"] == "charging") chargeRetry = retry else checkRetry = retry
                """{"status":99,"message":"pending"}"""
            },
            "pid", "pkey", setOf(CardType.VIETTEL), logger,
        )
        provider.submit(request)
        provider.check("REF1", request)
        assertEquals(false, chargeRetry)
        assertEquals(true, checkRetry)
    }
}
