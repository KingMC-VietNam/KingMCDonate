package net.kingmc.plugin.kingmcdonate.render

import java.util.UUID

fun interface QrSlotLookup {
    fun protectedSlot(uuid: UUID): Int?
}
