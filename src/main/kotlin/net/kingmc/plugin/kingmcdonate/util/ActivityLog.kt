package net.kingmc.plugin.kingmcdonate.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Asynchronous, append-only operational activity log written to its own rotating file
 * (`logs/activity.<gen>.log`), kept separate from the server console so admins can
 * review notable actions (webhooks received, admin commands, console rewards, point
 * changes) without gateway/console noise. Call sites record events inline via [log];
 * each write is handed to a single background thread so the caller never blocks on I/O
 * and entries preserve their arrival order. When disabled, [log] is a no-op.
 */
class ActivityLog private constructor(
    private val fileLogger: Logger?,
    private val handler: FileHandler?,
    private val fallback: PluginLogger,
) {

    private val executor = fileLogger?.let {
        Executors.newSingleThreadExecutor { r -> Thread(r, "KMD-ActivityLog").apply { isDaemon = true } }
    }

    /** Append one line `<timestamp> | CATEGORY | message`; no-op when logging is disabled. */
    fun log(category: String, message: String) {
        val logger = fileLogger ?: return
        val now = System.currentTimeMillis()
        executor?.execute {
            try {
                logger.info("${TS.get().format(Date(now))} | $category | $message")
            } catch (e: Exception) {
                fallback.error("Activity log write failed.", e)
            }
        }
    }

    /** Drain pending writes and close the file. Called on plugin disable. */
    fun close() {
        val ex = executor ?: return
        ex.shutdown()
        try {
            if (!ex.awaitTermination(3, TimeUnit.SECONDS)) ex.shutdownNow()
        } catch (e: InterruptedException) {
            ex.shutdownNow()
            Thread.currentThread().interrupt()
        }
        handler?.close()
    }

    companion object {
        // Only the single executor thread formats, but a ThreadLocal keeps it safe regardless.
        private val TS = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss") }

        /**
         * Build an [ActivityLog]. When [enabled] is false — or the file cannot be opened —
         * a no-op instance is returned so call sites never need a null check.
         * [maxSizeBytes] is the per-file rotation threshold; [maxFiles] rotated files are kept.
         */
        fun create(
            dataFolder: File,
            enabled: Boolean,
            maxSizeBytes: Int,
            maxFiles: Int,
            fallback: PluginLogger,
        ): ActivityLog {
            if (!enabled) return ActivityLog(null, null, fallback)
            return try {
                val dir = File(dataFolder, "logs").apply { mkdirs() }
                val handler = FileHandler("${dir.absolutePath}/activity.%g.log", maxSizeBytes, maxFiles, true).apply {
                    formatter = object : Formatter() {
                        override fun format(record: LogRecord) = record.message + System.lineSeparator()
                    }
                    level = Level.ALL
                }
                // Anonymous logger: not registered in the global namespace, so repeated
                // create() calls (reload, tests) never stack handlers on a shared instance.
                val logger = Logger.getAnonymousLogger().apply {
                    useParentHandlers = false
                    level = Level.ALL
                    addHandler(handler)
                }
                ActivityLog(logger, handler, fallback)
            } catch (e: Exception) {
                fallback.error("Activity log file could not be opened; activity logging disabled.", e)
                ActivityLog(null, null, fallback)
            }
        }
    }
}
