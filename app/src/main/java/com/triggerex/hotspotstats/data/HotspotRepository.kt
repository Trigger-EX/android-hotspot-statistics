package com.triggerex.hotspotstats.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Composes the readers into snapshots and client scans. */
class HotspotRepository(context: Context) {

    val shell = ShellRunner()
    private val stateReader = HotspotStateReader(context)
    private val interfaceReader = InterfaceReader(shell)
    private val upstreamReader = UpstreamReader(context)
    private val usageReader = UsageReader(context)
    private val scanner = ClientScanner(shell)
    val localHotspot = LocalHotspotController(context)

    private var iwAvailable: Boolean = false
    private var cachedRootConfig: HotspotConfig? = null
    private var neighbourNotes: List<String> = emptyList()
    private var cachedUsage: Pair<Long, UsageState>? = null

    fun changes(): Flow<Unit> = stateReader.changes()

    fun isSuPresent(): Boolean = shell.isSuPresent()

    suspend fun enableRoot(): Boolean {
        val granted = shell.requestRoot()
        iwAvailable = granted && scanner.isIwAvailable()
        cachedRootConfig = null
        return granted
    }

    fun disableRoot() {
        shell.dropRoot()
        iwAvailable = false
        cachedRootConfig = null
    }

    fun rootStatus() = RootStatus(
        suAvailable = shell.isSuPresent(),
        granted = shell.rootGranted,
        iwAvailable = iwAvailable,
    )

    fun notes(): List<String> = neighbourNotes

    suspend fun snapshot(): HotspotSnapshot = withContext(Dispatchers.Default) {
        val (apState, apSource) = stateReader.readApState()
        val tethered = stateReader.readTetheredInterfaces().orEmpty()
        val upstreamIfaces = upstreamReader.upstreamInterfaces()
        val interfaces = interfaceReader.read(tethered, upstreamIfaces, apState == ApState.ENABLED)
        val hotspot = interfaceReader.pickHotspotInterface(interfaces)

        val localConfig = (localHotspot.state.value as? LocalHotspotController.State.On)?.config
        val config = localConfig ?: readRootConfig()

        HotspotSnapshot(
            apState = apState,
            apStateSource = apSource,
            hotspotInterface = hotspot,
            interfaces = interfaces,
            config = config,
            upstream = runCatching { upstreamReader.read() }.getOrNull(),
            usage = usage(),
            deviceTraffic = usageReader.deviceTraffic(),
            root = rootStatus(),
            timestampMs = System.currentTimeMillis(),
        )
    }

    private suspend fun usage(): UsageState {
        val now = System.currentTimeMillis()
        cachedUsage?.let { (at, state) ->
            if (now - at < USAGE_CACHE_MS && state !is UsageState.NeedsPermission) return state
        }
        return usageReader.read().also { cachedUsage = now to it }
    }

    private suspend fun readRootConfig(): HotspotConfig? {
        if (!shell.rootGranted) return null
        cachedRootConfig?.let { return it }
        val config = SOFTAP_CONFIG_PATHS.firstNotNullOfOrNull { path ->
            shell.readFile(path, root = true)?.let(Parsers::parseSoftApXml)
        }
        cachedRootConfig = config
        return config
    }

    /**
     * Updates the client list. A [fullSweep] probes the whole subnet; otherwise only
     * known clients are re-measured and the kernel / iw tables are re-read.
     */
    suspend fun scanClients(
        hotspot: InterfaceInfo,
        previous: List<ConnectedClient>,
        fullSweep: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): List<ConnectedClient> = coroutineScope {
        val previousByKey = previous.associateBy { it.key }

        val probes = if (fullSweep) {
            scanner.sweep(hotspot, onProgress)
        } else {
            val gate = Semaphore(16)
            previous.mapNotNull { it.ip }.map { ip ->
                async { gate.withPermit { scanner.latency(ip)?.let { ClientScanner.ProbeResult(ip, it, emptyList()) } } }
            }.awaitAll().filterNotNull()
        }

        val neighbourRead = scanner.readNeighbours(hotspot.name)
        neighbourNotes = neighbourRead.notes
        val stations = if (iwAvailable) scanner.readStations(hotspot.name) else null
        val leases = scanner.readLeases()

        // Fingerprint and name hosts that are new or still unnamed.
        val candidates = (probes.map { it.ip } + neighbourRead.entries.map { it.ip }).distinct()
        val needsWork = candidates.filter { ip ->
            val old = previousByKey[ip]
            old == null || (old.hostname == null && fullSweep) || (old.openPorts.isEmpty() && fullSweep)
        }
        val gate = Semaphore(12)
        val extra = needsWork.map { ip ->
            async {
                gate.withPermit {
                    val name = scanner.resolveName(ip)
                    val fingerprint = scanner.fingerprint(ip)
                    Triple(ip, name, fingerprint)
                }
            }
        }.awaitAll()

        val names = extra.mapNotNull { (ip, name, _) -> name?.let { ip to it } }.toMap()
        val fingerprints = extra.mapNotNull { it.third }.associateBy { it.ip }
        val mergedProbes = probes.map { p ->
            val fp = fingerprints[p.ip]
            if (fp == null) p else p.copy(openPorts = (p.openPorts + fp.openPorts).distinct().sorted())
        } + fingerprints.values.filter { fp -> probes.none { it.ip == fp.ip } }

        ClientMerger.merge(
            ClientMerger.Inputs(
                previous = previousByKey,
                probes = mergedProbes,
                neighbours = neighbourRead.entries,
                stations = stations,
                leases = leases,
                names = names,
                ownIp = hotspot.ipv4?.address,
                nowMs = System.currentTimeMillis(),
            ),
        )
    }

    fun hasUsageAccess() = usageReader.hasUsageAccess()

    private companion object {
        const val USAGE_CACHE_MS = 30_000L
        val SOFTAP_CONFIG_PATHS = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml",
            "/data/misc/wifi/WifiConfigStoreSoftAp.xml",
        )
    }
}
