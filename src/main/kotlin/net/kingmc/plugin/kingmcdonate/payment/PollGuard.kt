package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Run [block] only when no pass is already in flight, skipping (with a debug log)
 * otherwise, and always clearing the in-flight flag afterwards. Used by the poll
 * services so a slow gateway cannot stack overlapping passes.
 */
internal inline fun AtomicBoolean.runExclusively(logger: PluginLogger, label: String, block: () -> Unit) {
    if (!compareAndSet(false, true)) {
        logger.debug { "Skipping $label poll; previous pass still running." }
        return
    }
    try {
        block()
    } finally {
        set(false)
    }
}
