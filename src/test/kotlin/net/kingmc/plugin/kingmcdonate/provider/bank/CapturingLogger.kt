package net.kingmc.plugin.kingmcdonate.provider.bank

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** A [PluginLogger] that records the WARNING lines it emits, so tests can assert on operator warnings. */
class CapturingLogger {

    val warnings = mutableListOf<String>()

    val plugin = PluginLogger(
        Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level == Level.WARNING) warnings += record.message
                }

                override fun flush() = Unit
                override fun close() = Unit
            })
        },
        debugMode = false,
    )

    fun warnedContaining(vararg fragments: String) =
        warnings.any { line -> fragments.all { line.contains(it, ignoreCase = true) } }
}
