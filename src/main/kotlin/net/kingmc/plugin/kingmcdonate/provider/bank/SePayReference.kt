package net.kingmc.plugin.kingmcdonate.provider.bank

/**
 * Derives the reference-code candidates from a SePay transfer, shared by polling and
 * the webhook so both match identically: SePay's auto-extracted `code` when present,
 * otherwise the exact `[A-Z0-9]` tokens of the transfer content. Matching against
 * these tokens (never a loose substring) keeps a short reference from matching inside
 * a longer one.
 */
object SePayReference {

    private val NON_ALNUM = Regex("[^A-Z0-9]+")

    /** Candidate references in priority order: the extracted code first, else content tokens. */
    fun candidates(code: String?, content: String?): List<String> {
        val extracted = code?.trim()?.uppercase()
        if (!extracted.isNullOrEmpty()) return listOf(extracted)
        val text = content ?: return emptyList()
        return text.uppercase().split(NON_ALNUM).filter { it.isNotEmpty() }
    }
}
