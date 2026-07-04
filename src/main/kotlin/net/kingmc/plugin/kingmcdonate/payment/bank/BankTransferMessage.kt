package net.kingmc.plugin.kingmcdonate.payment.bank

/**
 * Pure builder for the manual bank-transfer message lines shown alongside the QR (both Java
 * and Bedrock). Substitutes `{bank}`, `{account}`, `{amount}`, `{ref}`, `{holder}` into the
 * configured template lines; a line referencing `{holder}` is dropped when no holder is set.
 * Colour translation happens at the call site, so this stays format-agnostic and testable.
 */
object BankTransferMessage {
    fun build(
        lines: List<String>,
        bank: String,
        account: String,
        amount: String,
        ref: String,
        holder: String?,
    ): List<String> {
        val h = holder?.trim().orEmpty()
        return lines
            .filter { h.isNotEmpty() || !it.contains("{holder}") }
            .map {
                it.replace("{bank}", bank)
                    .replace("{account}", account)
                    .replace("{amount}", amount)
                    .replace("{ref}", ref)
                    .replace("{holder}", h)
            }
    }
}
