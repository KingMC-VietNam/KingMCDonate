package net.kingmc.plugin.kingmcdonate.provider.bank

/**
 * Builds the uppercase haystack a SePay transfer offers for reference matching: the
 * auto-extracted `code` joined to the transfer content by a space, so a reference
 * cannot bridge the code/content boundary. Shared by polling and the webhook so both
 * match identically — an order's plain reference is confirmed when this text contains it.
 */
object SePayReference {

    /** Uppercase `code + " " + content`; either part may be null. */
    fun searchText(code: String?, content: String?): String =
        ((code ?: "") + " " + (content ?: "")).uppercase()
}
