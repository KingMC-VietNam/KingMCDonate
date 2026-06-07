package net.kingmc.plugin.kingmcdonate.util

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Thin wrapper over the Bukkit plugin [Logger] (already prefixed with the plugin
 * name) that adds a debug-mode guard. Warnings and errors are always emitted;
 * [debug] lines only when [debugMode] is enabled.
 */
class PluginLogger(
    @PublishedApi internal val logger: Logger,
    @Volatile var debugMode: Boolean = false,
) {

    fun info(message: String) = logger.info(message)

    fun warn(message: String) = logger.warning(message)

    fun error(message: String) = logger.severe(message)

    fun error(message: String, throwable: Throwable) =
        logger.log(Level.SEVERE, message, throwable)

    /**
     * Emitted only when debug mode is enabled. Takes a lambda so the message is
     * built lazily — no string work happens when debug is off.
     */
    inline fun debug(message: () -> String) {
        if (debugMode) logger.info("[DEBUG] ${message()}")
    }
}
