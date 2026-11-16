package com.triggerex.hotspotstats.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.triggerex.hotspotstats.data.ConnectedClient
import com.triggerex.hotspotstats.data.HotspotRepository
import com.triggerex.hotspotstats.data.HotspotSnapshot
import com.triggerex.hotspotstats.data.LocalHotspotController
import com.triggerex.hotspotstats.data.ScanProgress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
)

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

    init {
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

    fun startLocalHotspot() = repo.localHotspot.start()
    fun stopLocalHotspot() = repo.localHotspot.stop()

    private suspend fun snapshotLoop() {
        while (true) {
            active.first { it }
            val snapshot = runCatching { repo.snapshot() }.getOrNull()
            if (snapshot != null) {
                _state.update { it.copy(snapshot = snapshot, notes = repo.notes()) }
                val iface = snapshot.hotspotInterface
                val key = iface?.let { "${it.name}/${it.ipv4?.address}" }
                if (key != scannedInterface) {
                    scannedInterface = key
                    if (iface == null) {
                        _state.update { it.copy(clients = emptyList(), scanProgress = null) }
                    } else {
                        scanRequests.trySend(Unit)
                    }
                }
            }
            withTimeoutOrNull(SNAPSHOT_INTERVAL_MS) { refreshRequests.receive() }
        }
    }

    private suspend fun clientLoop() {
        var requested = true
        while (true) {
            active.first { it }
            val current = _state.value
            val hotspot = current.snapshot?.hotspotInterface
            if (hotspot != null) {
                val now = System.currentTimeMillis()
                val full = requested || current.lastFullScanMs == null ||
                    now - current.lastFullScanMs > FULL_SCAN_INTERVAL_MS
                val clients = runCatching {
                    repo.scanClients(hotspot, current.clients, full) { probed, total ->
                        _state.update { it.copy(scanProgress = ScanProgress(probed, total)) }
                    }
                }.getOrElse { e ->
                    _state.update { it.copy(message = "Scan failed: ${e.message}") }
                    current.clients
                }
                _state.update {
                    it.copy(
                        clients = clients,
                        scanProgress = null,
                        notes = repo.notes(),
                        lastFullScanMs = if (full) System.currentTimeMillis() else it.lastFullScanMs,
                    )
                }
            }
            requested = withTimeoutOrNull(QUICK_SCAN_INTERVAL_MS) { scanRequests.receive() } != null
        }
    }

    private companion object {
        const val KEY_ROOT = "root_enabled"
        const val SNAPSHOT_INTERVAL_MS = 2_000L
        const val QUICK_SCAN_INTERVAL_MS = 8_000L
        const val FULL_SCAN_INTERVAL_MS = 45_000L
    }
}
