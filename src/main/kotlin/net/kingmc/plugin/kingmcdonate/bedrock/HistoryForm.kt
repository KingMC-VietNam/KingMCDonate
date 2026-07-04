package net.kingmc.plugin.kingmcdonate.bedrock

import net.kingmc.plugin.kingmcdonate.config.BedrockFormsConfig
import net.kingmc.plugin.kingmcdonate.config.MessageKeys
import net.kingmc.plugin.kingmcdonate.config.Messages
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.payment.card.CardDisplay
import net.kingmc.plugin.kingmcdonate.payment.model.PaymentStatus
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import net.kingmc.plugin.kingmcdonate.util.Text
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm
import java.util.UUID

/**
 * Bedrock history form: the read-only card+bank list of the Java [HistoryMenu], rendered as a
 * SimpleForm. Rows are loaded off the main thread, then the form is sent on the player's region
 * thread. Message-sourced text (bank label, status) is stripped of colour codes since Bedrock
 * forms show plain text only.
 */
class HistoryForm(
    private val cardPaymentDao: CardPaymentDao,
    private val bankPaymentDao: BankPaymentDao,
    private val forms: BedrockForms,
    private val scheduler: Scheduler,
    private val messages: () -> Messages,
    private val formConfig: () -> BedrockFormsConfig,
) {

    private data class Row(val createdAt: Long, val label: String, val amount: Long, val status: PaymentStatus)

    /** Returns true when this player is handled by the form; the actual send happens after an async load. */
    fun trySend(player: Player): Boolean {
        val fc = formConfig()
        if (!FormGate.shouldUse(fc.enabled, fc.history.enabled, forms.isAvailable, forms.isBedrock(player))) return false
        scheduler.runIo {
            val rows = loadRows(player.uniqueId)
            scheduler.runAtEntity(player) { send(player, rows) }
        }
        return true
    }

    private fun send(player: Player, rows: List<Row>) {
        val h = formConfig().history
        val content = if (rows.isEmpty()) h.empty else rows.joinToString("\n") { format(h.entryFormat, it) }
        val form = SimpleForm.builder().title(h.title).content(content).button(h.close).build()
        forms.send(player, form)
    }

    private fun format(template: String, row: Row): String =
        template
            .replace("{time}", CardDisplay.time(row.createdAt))
            .replace("{label}", plain(row.label))
            .replace("{amount}", Text.formatMoney(row.amount))
            .replace("{status}", plain(CardDisplay.statusText(row.status, messages())))

    private fun loadRows(uuid: UUID): List<Row> {
        val bankLabel = messages().get(MessageKeys.HISTORY_BANK_LABEL)
        val cards = cardPaymentDao.findByPlayer(uuid, MAX_ENTRIES).map { Row(it.createdAt, it.cardType, it.amount, it.status) }
        val banks = bankPaymentDao.findByPlayer(uuid, MAX_ENTRIES).map { Row(it.createdAt, bankLabel, it.amount, it.status) }
        return (cards + banks).sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
    }

    private fun plain(text: String): String = COLOR_CODE.replace(text, "")

    companion object {
        private const val MAX_ENTRIES = 54
        private val COLOR_CODE = Regex("[§&][0-9A-Fa-fK-Ok-oRrXx]")
    }
}
