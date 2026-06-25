package net.kingmc.plugin.kingmcdonate.util

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Small hashing helpers for gateway request signing and webhook verification. */
object Hashing {

    /** Lower-case hex MD5 of [input] (UTF-8). */
    fun md5Hex(input: String): String = hex(MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)))

    /** Lower-case hex HMAC-SHA256 of [message] under [secret] (both UTF-8). */
    fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /** Constant-time equality over UTF-8 bytes; use for signature/key comparison. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }
}
