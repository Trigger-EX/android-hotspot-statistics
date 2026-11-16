package com.triggerex.hotspotstats.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs shell commands, optionally through `su`, with a hard timeout. */
class ShellRunner {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    @Volatile
    var rootGranted: Boolean = false
        private set

    /** Whether the root shell has toybox `timeout`, so root commands can end themselves. */
    @Volatile
    private var rootHasTimeout: Boolean = false

    fun isSuPresent(): Boolean =
        SU_PATHS.any { File(it).exists() } ||
            System.getenv("PATH").orEmpty().split(':').any { File(it, "su").exists() }

    /** Asks for root. On Magisk/KernelSU this shows the grant prompt the first time. */
    suspend fun requestRoot(): Boolean {
        val result = run("id", root = true, timeoutMs = 20_000)
        rootGranted = result?.stdout?.contains("uid=0") == true
        rootHasTimeout = rootGranted && run("timeout 1 true", root = true)?.ok == true
        return rootGranted
    }

    fun dropRoot() {
        rootGranted = false
    }

    suspend fun run(command: String, root: Boolean = false, timeoutMs: Long = 5_000): Result? =
        withContext(Dispatchers.IO) {
            val process = try {
                if (root) {
                    // With Magisk the command runs under magiskd and with setuid su it runs as
                    // uid 0; the app can kill neither, so the root side must enforce its own limit.
                    val seconds = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
                    val wrapped = if (rootHasTimeout) "timeout $seconds sh -c ${shellQuote(command)}" else command
                    ProcessBuilder("su", "-c", wrapped).start()
                } else {
                    ProcessBuilder("sh", "-c", command).start()
                }
            } catch (_: Exception) {
                return@withContext null
            }
            // Blocking stream reads ignore cancellation, and a root child may keep the pipes
            // open after we destroy `su`. The readers therefore run detached (never awaited
            // without a limit) and are abandoned if stuck.
            val out = readerScope.async { runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("") }
            val err = readerScope.async { runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("") }
            try {
                val finished = try {
                    runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                } finally {
                    process.destroy()
                }
                if (!finished) return@withContext null
                val stdout = withTimeoutOrNull(READ_GRACE_MS) { out.await() }
                val stderr = withTimeoutOrNull(READ_GRACE_MS) { err.await() }.orEmpty()
                stdout?.let { Result(process.exitValue(), it, stderr) }
            } finally {
                if (!out.isCompleted || !err.isCompleted) {
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            }
        }

    /** Runs as root if root was granted, otherwise unprivileged. */
    suspend fun runBest(command: String, timeoutMs: Long = 5_000): Result? =
        run(command, root = rootGranted, timeoutMs = timeoutMs)

    suspend fun readFile(path: String, root: Boolean = rootGranted): String? {
        if (!root) {
            return withContext(Dispatchers.IO) {
                try {
                    File(path).takeIf { it.canRead() }?.readText()
                } catch (_: Exception) {
                    null
                }
            }
        }
        return run("cat '$path'", root = true)?.takeIf { it.ok }?.stdout
    }

    companion object {
        private const val READ_GRACE_MS = 1_000L
        private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su",
        )
    }
}
