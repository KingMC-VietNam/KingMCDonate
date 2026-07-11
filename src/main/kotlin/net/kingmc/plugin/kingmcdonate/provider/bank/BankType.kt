package net.kingmc.plugin.kingmcdonate.provider.bank

/**
 * A bank supported by Web2M's Bank V3 history API. Carries the VietQR [bin] (used to build the
 * QR image URL), the Web2M V3 endpoint [web2mPath], and whether the endpoint is [oneParam]
 * (OpenAPI banks take only the token; the rest take password/account/token). Values mirror
 * Web2M's Postman V3 collection.
 */
enum class BankType(val bin: String, val web2mPath: String, val oneParam: Boolean) {
    VCB("970436", "historyapivcbv3", false),
    BIDV("970418", "historyapibidvv3", false),
    BIDV_OPENAPI("970418", "historyapiopenbidvv3", true),
    MBBANK_OPENAPI("970422", "historyapiopenmbv3", true),
    ACB("970416", "historyapiacbv3", false),
    TECHCOMBANK("970407", "historyapitcbv3", false),
    MBBANK_LSGD("970422", "historyapimbv3", false),
    MBBANK_NOTI("970422", "historyapimbnotiv3", false),
    TPBANK("970423", "historyapitpbv3", false),
    ;

    companion object {
        /** Resolve a config `bank-type` value to a [BankType], case-insensitively; null if unknown/blank. */
        fun parse(raw: String?): BankType? {
            val name = raw?.trim()?.uppercase().orEmpty()
            if (name.isEmpty()) return null
            return entries.firstOrNull { it.name == name }
        }
    }
}
