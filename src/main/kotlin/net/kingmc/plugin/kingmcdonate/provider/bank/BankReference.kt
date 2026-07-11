package net.kingmc.plugin.kingmcdonate.provider.bank

/**
 * Builds the uppercase haystack a bank transfer offers for reference matching: an
 * optional gateway-extracted `code` joined to the transfer content by a space, so a
 * reference cannot bridge the code/content boundary. Shared by every gateway's polling
 * and webhook paths so both match identically — an order's plain reference is confirmed
 * when this text contains it.
 */
object BankReference {

    /** Uppercase `code + " " + content`; either part may be null. */
    fun searchText(code: String?, content: String?): String =
        ((code ?: "") + " " + (content ?: "")).uppercase()
}
