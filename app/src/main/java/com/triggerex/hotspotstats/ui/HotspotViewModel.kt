package com.triggerex.hotspotstats.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.triggerex.hotspotstats.capture.CaptureFile
import com.triggerex.hotspotstats.capture.CaptureHost
import com.triggerex.hotspotstats.capture.CaptureManager
import com.triggerex.hotspotstats.capture.CaptureRequest
import com.triggerex.hotspotstats.capture.CaptureService
import com.triggerex.hotspotstats.capture.CaptureState
import com.triggerex.hotspotstats.data.ConnectedClient
import com.triggerex.hotspotstats.data.HotspotRepository
import com.triggerex.hotspotstats.data.HotspotSnapshot
import com.triggerex.hotspotstats.data.LocalHotspotController
import com.triggerex.hotspotstats.data.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class UiState(
    val snapshot: HotspotSnapshot? = null,
    val clients: List<ConnectedClient> = emptyList(),
    val scanProgress: ScanProgress? = null,
    val lastFullScanMs: Long? = null,
    val notes: List<String> = emptyList(),
    val rootEnabled: Boolean = false,
    val rootRequesting: Boolean = false,
    val message: String? = null,
    val localHotspot: LocalHotspotController.State = LocalHotspotController.State.Off,
    /** Client keys selected for packet capture. */
    val captureSelection: Set<String> = emptySet(),
)

/** Capture limits chosen on the Capture tab. */
data class CaptureOptions(val durationSec: Int, val maxBytes: Long, val headersOnly: Boolean)

class HotspotViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HotspotRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Polling only runs while the UI is visible. */
    private val active = MutableStateFlow(false)
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var scannedInterface: String? = null

    val capture: StateFlow<CaptureState?> = CaptureManager.state
    val captureFiles: StateFlow<List<CaptureFile>> = CaptureManager.files

    init {
        CaptureManager.init(app)
        if (prefs.getBoolean(KEY_ROOT, false) && repo.isSuPresent()) setRootEnabled(true)

        viewModelScope.launch { snapshotLoop() }
        viewModelScope.launch { clientLoop() }
        viewModelScope.launch {
            repo.changes().collect {
                refreshRequests.trySend(Unit)
                scanRequests.trySend(Unit)
            }
        }
        viewModelScope.launch {
            repo.localHotspot.state.collect { s ->
                _state.update { it.copy(localHotspot = s) }
                refreshRequests.trySend(Unit)
            }
        }
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (isActive) refreshRequests.trySend(Unit)
    }

    fun rescan() {
        scanRequests.trySend(Unit)
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setRootEnabled(enabled: Boolean) {
        if (!enabled) {
            repo.disableRoot()
            prefs.edit().putBoolean(KEY_ROOT, false).apply()
            _state.update { it.copy(rootEnabled = false) }
            refreshRequests.trySend(Unit)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(rootRequesting = true) }
            val granted = repo.enableRoot()
            prefs.edit().putBoolean(KEY_ROOT, granted).apply()
            _state.update {
                it.copy(
                    rootEnabled = granted,
                    rootRequesting = false,
                    message = if (granted) null else "Root access was not granted.",
                )
            }
            refreshRequests.trySend(Unit)
            scanRequests.trySend(Unit)
        }
    }

    fun toggleCaptureHost(key: String) = _state.update {
        val sel = it.captureSelection
        it.copy(captureSelection = if (key in sel) sel - key else sel + key)
    }

    fun selectOnlyForCapture(key: String) = _state.update { it.copy(captureSelection = setOf(key)) }

    fun clearCaptureSelection() = _state.update { it.copy(captureSelection = emptySet()) }

    /** Starts a capture of the selected clients (all traffic if none). Returns an error or null. */
    fun startCapture(options: CaptureOptions): String? {
        val s = _state.value
        if (!s.rootEnabled) return "Packet capture needs root. Turn on root mode on the Overview tab."
        val iface = s.snapshot?.hotspotInterface ?: return "No active hotspot interface."
        val hosts = s.clients
            .filter { it.key in s.captureSelection }
            .map { CaptureHost(it.ip, it.mac, it.hostname ?: it.ip ?: it.mac ?: "host") }
        if (s.captureSelection.isNotEmpty() && hosts.isEmpty()) {
            return "The selected devices are no longer connected."
        }
        val request = CaptureRequest(
            iface = iface.name,
            hosts = hosts,
            durationSec = options.durationSec,
            maxBytes = options.maxBytes,
            snaplen = if (options.headersOnly) HEADERS_SNAPLEN else 65535,
        )
        val error = CaptureManager.start(request)
        if (error == null) CaptureService.startTracking(getApplication())
        return error
    }

    fun stopCapture() = CaptureManager.stop()

    fun deleteCapture(file: CaptureFile) = CaptureManager.delete(file.file)

    fun shareCapture(file: CaptureFile) = CaptureManager.shareIntent(file.file)

    fun startLocalHotspot() = repo.localHotspot.start()
    fun stopLocalHotspot() = repo.localHotspot.stop()

    override fun onCleared() {
        // A local-only hotspot reservation must not outlive the screen that owns it.
        repo.localHotspot.stop()
    }

    private suspend fun snapshotLoop() {
        // collectLatest cancels the running iteration as soon as the app leaves the foreground.
        active.collectLatest { isActive ->
            if (!isActive) return@collectLatest
            while (true) {
                val snapshot = try {
                    repo.snapshot()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (snapshot != null) _state.update { it.copy(snapshot = snapshot, notes = repo.notes()) }
                withTimeoutOrNull(SNAPSHOT_INTERVAL_MS) { refreshRequests.receive() }
            }
        }
    }

    private suspend fun clientLoop() {
        val hotspotKey = _state
            .map { s -> s.snapshot?.hotspotInterface?.let { "${it.name}/${it.ipv4?.address}/${it.ipv4?.prefixLength}" } }
            .distinctUntilChanged()
        combine(active, hotspotKey) { isActive, key -> isActive to key }
            .distinctUntilChanged()
            .collectLatest { (isActive, key) ->
                if (key != scannedInterface) {
                    // A different hotspot (or none): earlier clients belong to another subnet.
                    scannedInterface = key
                    _state.update { it.copy(clients = emptyList(), scanProgress = null, lastFullScanMs = null) }
                }
                if (!isActive || key == null) {
                    _state.update { it.copy(scanProgress = null) }
                    return@collectLatest
                }
                scanLoop()
            }
    }

    /** Runs until cancelled by [clientLoop] when the app goes inactive or the interface changes. */
    private suspend fun scanLoop() {
        val key = scannedInterface
        var requested = true
        while (true) {
            val current = _state.value
            val hotspot = current.snapshot?.hotspotInterface ?: return
            val startedAt = System.currentTimeMillis()
            val full = requested || current.lastFullScanMs == null ||
                startedAt - current.lastFullScanMs > FULL_SCAN_INTERVAL_MS
            val clients = try {
                repo.scanClients(hotspot, current.clients, full) { probed, total ->
                    if (scannedInterface == key) _state.update { it.copy(scanProgress = ScanProgress(probed, total)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Scan failed: ${e.message}", scanProgress = null) }
                null
            }
            currentCoroutineContext().ensureActive()
            if (clients != null) {
                _state.update {
                    it.copy(
                        clients = clients,
                        scanProgress = null,
                        notes = repo.notes(),
                        // Stamped at the start so slow sweeps on big subnets don't stretch the interval.
                        lastFullScanMs = if (full) startedAt else it.lastFullScanMs,
                    )
                }
            }
            requested = withTimeoutOrNull(QUICK_SCAN_INTERVAL_MS) { scanRequests.receive() } != null
        }
    }

    private companion object {
        /** Enough for Ethernet + IPv6 + TCP headers with options. */
        const val HEADERS_SNAPLEN = 128
        const val KEY_ROOT = "root_enabled"
        const val SNAPSHOT_INTERVAL_MS = 2_000L
        const val QUICK_SCAN_INTERVAL_MS = 8_000L
        const val FULL_SCAN_INTERVAL_MS = 45_000L
    }
}
