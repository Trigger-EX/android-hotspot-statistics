package com.triggerex.hotspotstats.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    fun isSuPresent(): Boolean =
        SU_PATHS.any { File(it).exists() } ||
            System.getenv("PATH").orEmpty().split(':').any { File(it, "su").exists() }

    /** Asks for root. On Magisk/KernelSU this shows the grant prompt the first time. */
    suspend fun requestRoot(): Boolean {
        val result = run("id", root = true, timeoutMs = 20_000)
        rootGranted = result?.stdout?.contains("uid=0") == true
        return rootGranted
    }

    fun dropRoot() {
        rootGranted = false
    }

    suspend fun run(command: String, root: Boolean = false, timeoutMs: Long = 5_000): Result? =
        withContext(Dispatchers.IO) {
            val process = try {
                if (root) {
                    ProcessBuilder("su", "-c", command).start()
                } else {
                    ProcessBuilder("sh", "-c", command).start()
                }
            } catch (_: Exception) {
                return@withContext null
            }
            // Blocking stream reads ignore coroutine cancellation, so the process itself is
            // destroyed on timeout or cancellation; that closes the streams and frees the readers.
            try {
                coroutineScope {
                    val out = async { process.inputStream.bufferedReader().readText() }
                    val err = async { process.errorStream.bufferedReader().readText() }
                    val finished = try {
                        runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                    } finally {
                        process.destroy()
                    }
                    val result = Result(if (finished) process.exitValue() else -1, out.await(), err.await())
                    result.takeIf { finished }
                }
            } catch (e: java.io.IOException) {
                null
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
        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su",
        )
    }
}
