package net.kingmc.plugin.kingmcdonate.payment.card

import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import java.text.SimpleDateFormat
import java.util.Date

/** Shared presentation helpers for payment history views. Display text comes from `messages.yml`. */
object CardDisplay {

    private val timeFormat = SimpleDateFormat("dd/MM HH:mm")

    fun statusText(status: PaymentStatus, messages: Messages): String = messages.get(
        when (status) {
            PaymentStatus.SUCCESS -> MessageKeys.STATUS_SUCCESS
            PaymentStatus.FAILED -> MessageKeys.STATUS_FAILED
            PaymentStatus.WAITING -> MessageKeys.STATUS_WAITING
            PaymentStatus.PENDING -> MessageKeys.STATUS_PENDING
        },
    )

    fun time(epochMillis: Long): String = synchronized(timeFormat) { timeFormat.format(Date(epochMillis)) }
}
