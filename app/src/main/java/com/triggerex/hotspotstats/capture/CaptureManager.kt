package com.triggerex.hotspotstats.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class CapturedPacket(val epochMs: Long, val length: Int, val summary: String)

data class CaptureState(
    val request: CaptureRequest,
    val file: File,
    val startedAtMs: Long,
    val running: Boolean,
    val stopping: Boolean = false,
    /** True once the helper has opened the interface. */
    val capturing: Boolean = false,
    val packets: Long = 0,
    val bytes: Long = 0,
    val kernelDrops: Long = 0,
    val seen: Long = 0,
    val elapsedMs: Long = 0,
    val fileBytes: Long = 0,
    /** Newest first. */
    val recent: List<CapturedPacket> = emptyList(),
    val error: String? = null,
    val endReason: String? = null,
)

data class CaptureFile(val file: File, val sizeBytes: Long, val modifiedMs: Long)

/**
 * Runs at most one capture for the whole process, so it survives the activity and is shared
 * with [CaptureService]. The root helper writes pcap to its stdout and this class writes the
 * file, which keeps the file owned and labelled as the app's own.
 */
// Holds only the application context, which lives as long as the process, so it cannot leak.
@SuppressLint("StaticFieldLeak")
object CaptureManager {

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<CaptureState?>(null)
    val state: StateFlow<CaptureState?> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<CaptureFile>>(emptyList())
    val files: StateFlow<List<CaptureFile>> = _files.asStateFlow()

    @Volatile
    private var process: Process? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        refreshFiles()
    }

    val isRunning: Boolean get() = _state.value?.running == true

    private fun capturesDir() = File(app.filesDir, "captures").apply { mkdirs() }

    fun helperAvailable(): Boolean = helper().exists()

    private fun helper() = File(app.applicationInfo.nativeLibraryDir, "libhscapture.so")

    /** Starts a capture. Returns an error message, or null on success. */
    @Synchronized
    fun start(request: CaptureRequest): String? {
        if (isRunning) return "A capture is already running."
        val bin = helper()
        if (!bin.exists()) return "The capture helper is missing for this device's CPU type."
        val command = try {
            CaptureProtocol.command(bin.path, request)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        val file = File(capturesDir(), fileName(request))
        val proc = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            return "Could not run su: ${e.message}"
        }
        process = proc
        val written = AtomicLong(0)
        _state.value = CaptureState(request, file, System.currentTimeMillis(), running = true)

        val pump = scope.launch { pumpOutput(proc, file, written) }
        val reader = scope.launch { readStatus(proc, written) }
        scope.launch {
            val code = runInterruptible { proc.waitFor() }
            // Give the readers a moment to drain what the helper wrote before exiting.
            withTimeoutOrNull(3_000) { pump.join(); reader.join() }
            process = null
            _state.update { s ->
                s?.copy(
                    running = false,
                    stopping = false,
                    fileBytes = written.get(),
                    endReason = s.endReason ?: if (s.error == null) "Helper exited (code $code)" else null,
                    error = s.error ?: if (!s.capturing && code != 0) "Root access was denied or the helper could not start (code $code)." else null,
                )
            }
            // A capture that never started leaves only a header; don't keep it.
            if (_state.value?.capturing != true) file.delete()
            refreshFiles()
        }
        return null
    }

    /** Asks the helper to finish cleanly; falls back to closing its input, then destroying su. */
    fun stop() {
        val proc = process ?: return
        _state.update { it?.copy(stopping = true) }
        scope.launch {
            runCatching {
                proc.outputStream.write("q\n".toByteArray())
                proc.outputStream.flush()
            }
            if (!runInterruptible { proc.waitFor(3, TimeUnit.SECONDS) }) {
                runCatching { proc.outputStream.close() }
                if (!runInterruptible { proc.waitFor(2, TimeUnit.SECONDS) }) proc.destroy()
            }
        }
    }

    private fun pumpOutput(proc: Process, file: File, written: AtomicLong) {
        try {
            FileOutputStream(file).use { out ->
                val input = proc.inputStream
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written.addAndGet(n.toLong())
                }
            }
        } catch (e: IOException) {
            _state.update { it?.copy(error = it.error ?: "Writing the capture failed: ${e.message}") }
        }
    }

    private fun readStatus(proc: Process, written: AtomicLong) {
        val recent = ArrayDeque<CapturedPacket>()
        var lastPublish = 0L
        val unknown = StringBuilder()
        try {
            proc.errorStream.bufferedReader().forEachLine { line ->
                val event = CaptureProtocol.parse(line)
                if (event == null) {
                    // su's own messages (e.g. "Permission denied") are not protocol lines.
                    if (unknown.length < 500) unknown.append(line).append('\n')
                    return@forEachLine
                }
                if (event is CaptureEvent.Packet) {
                    recent.addFirst(CapturedPacket(event.epochMs, event.length, event.summary))
                    while (recent.size > MAX_RECENT) recent.removeLast()
                }
                val now = System.currentTimeMillis()
                if (event is CaptureEvent.Packet && now - lastPublish < PUBLISH_INTERVAL_MS) return@forEachLine
                lastPublish = now
                val snapshot = recent.toList()
                _state.update { s ->
                    s ?: return@update null
                    when (event) {
                        is CaptureEvent.Started -> s.copy(capturing = true)
                        is CaptureEvent.Stats -> s.copy(
                            packets = event.packets, bytes = event.bytes, kernelDrops = event.drops,
                            seen = event.seen, elapsedMs = event.elapsedMs, fileBytes = written.get(), recent = snapshot,
                        )
                        is CaptureEvent.Packet -> s.copy(recent = snapshot, fileBytes = written.get())
                        is CaptureEvent.Error -> s.copy(error = event.message)
                        is CaptureEvent.Ended -> s.copy(
                            packets = event.packets, bytes = event.bytes, kernelDrops = event.drops,
                            endReason = CaptureProtocol.endReasonText(event.reason), recent = snapshot,
                        )
                    }
                }
            }
        } catch (_: IOException) {
            // Stream closed when the process ended.
        }
        if (unknown.isNotBlank()) {
            _state.update { s -> s?.takeIf { !it.capturing && it.error == null }?.copy(error = unknown.toString().trim()) ?: s }
        }
    }

    fun refreshFiles() {
        if (!::app.isInitialized) return
        _files.value = capturesDir().listFiles { f -> f.extension == "pcap" }
            .orEmpty()
            .map { CaptureFile(it, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedMs }
    }

    fun delete(file: File) {
        if (isRunning && _state.value?.file == file) return
        file.delete()
        if (_state.value?.file == file && _state.value?.running == false) _state.value = null
        refreshFiles()
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.tcpdump.pcap")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share capture").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun fileName(request: CaptureRequest): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val label = when (request.hosts.size) {
            0 -> "all"
            1 -> request.hosts[0].label.replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(24).ifEmpty { "host" }
            else -> "${request.hosts.size}-hosts"
        }
        return "hotspot_${stamp}_$label.pcap"
    }

    private const val MAX_RECENT = 300
    private const val PUBLISH_INTERVAL_MS = 300L
}
