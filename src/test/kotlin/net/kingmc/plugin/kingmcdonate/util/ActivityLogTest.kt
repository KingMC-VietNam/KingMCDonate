package net.kingmc.plugin.kingmcdonate.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

class ActivityLogTest {

    @TempDir
    lateinit var tempDir: File

    private val fallback = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)

    @Test
    fun `writes formatted category lines to the rotating file`() {
        val log = ActivityLog.create(tempDir, enabled = true, maxSizeBytes = 1_000_000, maxFiles = 3, fallback = fallback)
        log.log("WEBHOOK", "sepay tx=1 amount=50000")
        log.log("COMMAND", "give by Admin")
        log.close() // shuts down the writer thread and flushes the handler

        val file = File(tempDir, "logs/activity.0.log")
        assertTrue(file.exists(), "expected activity log file to be created")
        val text = file.readText()
        assertTrue(text.contains("| WEBHOOK | sepay tx=1 amount=50000"), "missing WEBHOOK line in: $text")
        assertTrue(text.contains("| COMMAND | give by Admin"), "missing COMMAND line in: $text")
    }

    @Test
    fun `preserves arrival order of entries`() {
        val log = ActivityLog.create(tempDir, enabled = true, maxSizeBytes = 1_000_000, maxFiles = 3, fallback = fallback)
        repeat(20) { log.log("N", it.toString()) }
        log.close()

        val bodies = File(tempDir, "logs/activity.0.log").readLines().map { it.substringAfterLast("| N | ") }
        assertTrue(bodies == (0 until 20).map { it.toString() }, "entries out of order: $bodies")
    }

    @Test
    fun `disabled log is a no-op and creates no file`() {
        val log = ActivityLog.create(tempDir, enabled = false, maxSizeBytes = 1000, maxFiles = 1, fallback = fallback)
        log.log("X", "y")
        log.close()

        assertFalse(File(tempDir, "logs").exists(), "disabled activity log must not create a logs directory")
    }
}
