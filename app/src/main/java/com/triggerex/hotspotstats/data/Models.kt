package com.triggerex.hotspotstats.data

/** Where a piece of information came from, shown next to values in the UI. */
enum class Source(val label: String) {
    SYSTEM_API("Android API"),
    HIDDEN_API("Hidden Android API"),
    BROADCAST("System broadcast"),
    INTERFACE("Network interface"),
    SYSFS("Kernel /sys counters"),
    ARP("ARP table"),
    NEIGHBOUR("ip neigh"),
    SUBNET_SCAN("Subnet scan"),
    IW("iw station dump (root)"),
    SOFTAP_CONFIG("Saved hotspot config (root)"),
    LOCAL_ONLY("Local-only hotspot"),
}

enum class ApState(val label: String) {
    DISABLED("Off"),
    DISABLING("Turning off"),
    ENABLED("On"),
    ENABLING("Turning on"),
    FAILED("Failed"),
    UNKNOWN("Unknown");

    companion object {
        /** Values of the hidden `WifiManager.WIFI_AP_STATE_*` constants. */
        fun fromWifiManagerValue(value: Int): ApState = when (value) {
            10 -> DISABLING
            11 -> DISABLED
            12 -> ENABLING
            13 -> ENABLED
            14 -> FAILED
            else -> UNKNOWN
        }
    }
}

data class InetAddressInfo(
    val address: String,
    val prefixLength: Int,
    val isIpv6: Boolean,
    val broadcast: String?,
    val scope: String,
)

data class InterfaceInfo(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isVirtual: Boolean,
    val mtu: Int?,
    val macAddress: String?,
    val addresses: List<InetAddressInfo>,
    val counters: TrafficCounters?,
    val rxRateBps: Double?,
    val txRateBps: Double?,
    val kind: InterfaceKind,
    /** True when the system tethering broadcast lists this interface as tethered. */
    val tethered: Boolean,
    /** True when this interface carries a network the device itself uses (the upstream). */
    val isUpstream: Boolean,
) {
    val ipv4: InetAddressInfo? get() = addresses.firstOrNull { !it.isIpv6 }
}

enum class InterfaceKind(val label: String) {
    WIFI_HOTSPOT("Wi-Fi hotspot"),
    WIFI("Wi-Fi"),
    WIFI_DIRECT("Wi-Fi Direct"),
    USB_TETHER("USB tethering"),
    BLUETOOTH_TETHER("Bluetooth tethering"),
    ETHERNET("Ethernet"),
    CELLULAR("Cellular"),
    VPN("VPN"),
    LOOPBACK("Loopback"),
    OTHER("Other"),
}

data class TrafficCounters(
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val txPackets: Long,
    val rxErrors: Long,
    val txErrors: Long,
    val rxDropped: Long,
    val txDropped: Long,
    val timestampMs: Long,
)

/** Saved soft-AP configuration, from root or from a local-only hotspot. */
data class HotspotConfig(
    val ssid: String?,
    val bssid: String? = null,
    val passphrase: String?,
    val security: String?,
    val band: String?,
    val channel: Int? = null,
    val hidden: Boolean? = null,
    val maxClients: Int? = null,
    val autoShutdown: Boolean? = null,
    val source: Source,
)

/** Per-station details from `iw dev <if> station dump`. */
data class StationInfo(
    val mac: String,
    val signalDbm: Int?,
    val signalAvgDbm: Int?,
    val txBitrateMbps: Double?,
    val rxBitrateMbps: Double?,
    val rxBytes: Long?,
    val txBytes: Long?,
    val rxPackets: Long?,
    val txPackets: Long?,
    val txRetries: Long?,
    val txFailed: Long?,
    val inactiveMs: Long?,
    val connectedSeconds: Long?,
    val authorized: Boolean?,
)

data class NeighbourEntry(
    val ip: String,
    val mac: String?,
    val device: String?,
    val state: String?,
    val source: Source,
)

data class ConnectedClient(
    /** Null for a station that `iw` reports but whose IP is not yet known. */
    val ip: String?,
    val mac: String?,
    val hostname: String?,
    val hostnameSource: String?,
    val deviceHint: String?,
    val isRandomizedMac: Boolean,
    val latencyMs: Double?,
    val openPorts: List<Int>,
    val neighbourState: String?,
    val station: StationInfo?,
    val sources: Set<Source>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
) {
    val key: String get() = ip ?: mac ?: "?"
}

data class CellularInfo(
    val operatorName: String?,
    val networkGeneration: String?,
    val signalLevel: Int?,
    val signalDbm: Int?,
    val signalDetails: List<Pair<String, String>>,
    val roaming: Boolean?,
)

data class UpstreamInfo(
    val transport: String,
    val interfaceName: String?,
    val isMetered: Boolean,
    val isValidated: Boolean,
    val downstreamKbps: Int?,
    val upstreamKbps: Int?,
    val signalStrength: Int?,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val gateway: String?,
    val privateDns: String?,
    val signalDetails: List<Pair<String, String>>,
    val cellular: CellularInfo?,
)

/** Hotspot data usage from NetworkStatsManager (UID_TETHERING bucket). */
data class TetheringUsage(
    val todayRx: Long,
    val todayTx: Long,
    val last30DaysRx: Long,
    val last30DaysTx: Long,
)

sealed interface UsageState {
    data object NeedsPermission : UsageState
    data class Available(val usage: TetheringUsage) : UsageState
    data class Error(val message: String) : UsageState
}

data class DeviceTraffic(
    val totalRx: Long,
    val totalTx: Long,
    val mobileRx: Long,
    val mobileTx: Long,
)

data class RootStatus(
    val suAvailable: Boolean,
    val granted: Boolean,
    val iwAvailable: Boolean,
)

data class ScanProgress(val probed: Int, val total: Int)

data class HotspotSnapshot(
    val apState: ApState,
    val apStateSource: Source?,
    val hotspotInterface: InterfaceInfo?,
    val interfaces: List<InterfaceInfo>,
    val config: HotspotConfig?,
    val upstream: UpstreamInfo?,
    val usage: UsageState,
    val deviceTraffic: DeviceTraffic?,
    val root: RootStatus,
    val timestampMs: Long,
)
