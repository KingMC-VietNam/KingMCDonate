package net.kingmc.plugin.kingmcdonate.payment

import java.security.SecureRandom

/**
 * Generates network-unique payment reference codes: a fixed-length run of
 * `[A-Z0-9]`. Codes are matched against transfer contents by exact token, so the
 * charset is kept to unambiguous upper-case alphanumerics. Uniqueness is enforced
 * by the database (UNIQUE column); callers regenerate on a collision.
 */
object ReferenceCode {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val LENGTH = 10
    private val random = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}
