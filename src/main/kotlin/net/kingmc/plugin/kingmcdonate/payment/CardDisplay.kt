package net.kingmc.plugin.kingmcdonate.payment

import java.text.SimpleDateFormat
import java.util.Date

/** Shared presentation helpers for card payments (history views). */
object CardDisplay {

    private val timeFormat = SimpleDateFormat("dd/MM HH:mm")

    fun statusText(status: PaymentStatus): String = when (status) {
        PaymentStatus.SUCCESS -> "&aThành công"
        PaymentStatus.FAILED -> "&cThất bại"
        PaymentStatus.WAITING -> "&eĐang xử lý"
        PaymentStatus.PENDING -> "&7Đang chờ"
    }

    fun time(epochMillis: Long): String = synchronized(timeFormat) { timeFormat.format(Date(epochMillis)) }
}
