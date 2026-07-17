package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** A [PluginLogger] that records the WARNING and SEVERE lines it emits, so tests can assert on them. */
class CapturingLogger {

    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    val plugin = PluginLogger(
        Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) {
                    when (record.level) {
                        Level.WARNING -> warnings += record.message
                        Level.SEVERE -> errors += record.message
                        else -> Unit
                    }
                }

                override fun flush() = Unit
                override fun close() = Unit
            })
        },
        debugMode = false,
    )

    fun warnedContaining(vararg fragments: String) =
        warnings.any { line -> fragments.all { line.contains(it, ignoreCase = true) } }

    fun erroredContaining(vararg fragments: String) =
        errors.any { line -> fragments.all { line.contains(it, ignoreCase = true) } }
}
