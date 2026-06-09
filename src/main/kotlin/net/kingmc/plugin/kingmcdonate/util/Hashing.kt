package net.kingmc.plugin.kingmcdonate.util

import java.security.MessageDigest

/** Small hashing helpers for gateway request signing. */
object Hashing {

    /** Lower-case hex MD5 of [input] (UTF-8). */
    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
