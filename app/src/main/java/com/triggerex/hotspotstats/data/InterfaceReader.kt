package com.triggerex.hotspotstats.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** Lists network interfaces, classifies them and tracks their traffic counters and rates. */
class InterfaceReader(private val shell: ShellRunner) {

    private val previousCounters = mutableMapOf<String, TrafficCounters>()
    private val macCache = mutableMapOf<String, String?>()

    /** Last root-read counters and rates per interface; root reads are throttled. */
    private val rootReads = mutableMapOf<String, Triple<TrafficCounters, Double?, Double?>>()

    suspend fun read(
        tethered: Set<String>,
        upstreamInterfaces: Set<String>,
        apEnabled: Boolean,
    ): List<InterfaceInfo> = withContext(Dispatchers.IO) {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        interfaces.map { ni ->
            val name = ni.name
            val addresses = ni.interfaceAddresses.mapNotNull { ia ->
                val addr = ia.address ?: return@mapNotNull null
                InetAddressInfo(
                    address = addr.hostAddress?.substringBefore('%') ?: return@mapNotNull null,
                    prefixLength = ia.networkPrefixLength.toInt(),
                    isIpv6 = addr is Inet6Address,
                    broadcast = ia.broadcast?.hostAddress,
                    scope = when {
                        addr.isLoopbackAddress -> "loopback"
                        addr.isLinkLocalAddress -> "link-local"
                        addr is Inet4Address && Parsers.isPrivateIpv4(addr.hostAddress.orEmpty()) -> "private"
                        addr is Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc -> "unique-local"
                        else -> "global"
                    },
                )
            }
            val isUpstream = name in upstreamInterfaces
            val isTethered = name in tethered
            val kind = classify(name, isTethered, isUpstream, apEnabled, addresses)
            val wantsCounters = kind != InterfaceKind.LOOPBACK
            val privileged = isTethered || kind == InterfaceKind.WIFI_HOTSPOT
            val (counters, rxRate, txRate) = if (wantsCounters) countersAndRates(name, privileged) else Triple(null, null, null)

            InterfaceInfo(
                name = name,
                displayName = ni.displayName ?: name,
                isUp = runCatching { ni.isUp }.getOrDefault(false),
                isVirtual = ni.isVirtual,
                mtu = runCatching { ni.mtu }.getOrNull()?.takeIf { it > 0 },
                macAddress = readMac(ni),
                addresses = addresses,
                counters = counters,
                rxRateBps = rxRate,
                txRateBps = txRate,
                kind = kind,
                tethered = isTethered,
                isUpstream = isUpstream,
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
    }

    /** Picks the interface serving the Wi-Fi hotspot, preferring what tethering reports. */
    fun pickHotspotInterface(interfaces: List<InterfaceInfo>): InterfaceInfo? =
        interfaces
            .filter { it.kind == InterfaceKind.WIFI_HOTSPOT && it.isUp && it.ipv4 != null }
            .sortedByDescending { it.tethered }
            .firstOrNull()

    private fun classify(
        name: String,
        tethered: Boolean,
        upstream: Boolean,
        apEnabled: Boolean,
        addresses: List<InetAddressInfo>,
    ): InterfaceKind {
        val n = name.lowercase()
        return when {
            n == "lo" -> InterfaceKind.LOOPBACK
            n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("ipsec") || n.startsWith("wg") -> InterfaceKind.VPN
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("seth") ||
                n.startsWith("pdp") || n.startsWith("v4-") || n.startsWith("rev_rmnet") -> InterfaceKind.CELLULAR
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> InterfaceKind.USB_TETHER
            n.startsWith("bt-pan") || n.startsWith("bnep") -> InterfaceKind.BLUETOOTH_TETHER
            n.startsWith("p2p") -> InterfaceKind.WIFI_DIRECT
            n.startsWith("eth") -> InterfaceKind.ETHERNET
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wigig") ->
                InterfaceKind.WIFI_HOTSPOT
            n.startsWith("wlan") || n.startsWith("wl") -> when {
                tethered -> InterfaceKind.WIFI_HOTSPOT
                // With the AP on, a Wi-Fi interface that the device is not using as its own
                // network but that holds a private IPv4 address is the hotspot side.
                apEnabled && !upstream && addresses.any { !it.isIpv6 && it.scope == "private" } ->
                    InterfaceKind.WIFI_HOTSPOT
                else -> InterfaceKind.WIFI
            }
            tethered -> InterfaceKind.WIFI_HOTSPOT
            else -> InterfaceKind.OTHER
        }
    }

    private suspend fun readMac(ni: NetworkInterface): String? {
        val key = "${ni.name}/${shell.rootGranted}"
        if (key in macCache) return macCache[key]
        // Android 11+ returns null here for normal apps; sysfs or root may still work.
        val fromApi = runCatching { ni.hardwareAddress }.getOrNull()
            ?.takeIf { it.size == 6 }
            ?.joinToString(":") { "%02x".format(it) }
        val path = "/sys/class/net/${ni.name}/address"
        val mac = fromApi
            ?: Parsers.normalizeMac(shell.readFile(path, root = false))
            ?: if (shell.rootGranted) Parsers.normalizeMac(shell.readFile(path, root = true)) else null
        macCache[key] = mac
        return mac
    }

    private suspend fun countersAndRates(name: String, privileged: Boolean): Triple<TrafficCounters?, Double?, Double?> {
        val dir = "/sys/class/net/$name/statistics"
        val values = COUNTER_FILES.map { f ->
            try {
                File(dir, f).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }
        if (values.all { it != null }) {
            val counters = toCounters(values.map { it!! })
            val (rx, tx) = rates(name, counters)
            return Triple(counters, rx, tx)
        }
        if (!privileged || !shell.rootGranted) return Triple(null, null, null)

        // Each su call costs a process (and a toast / log entry on Magisk), so reuse the
        // last root read for a few seconds instead of spawning su on every refresh.
        val cached = rootReads[name]
        if (cached != null && System.currentTimeMillis() - cached.first.timestampMs < ROOT_READ_INTERVAL_MS) return cached
        val out = shell.run("cd $dir && cat ${COUNTER_FILES.joinToString(" ")}", root = true)
        val parsed = out?.stdout?.lines()?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.size == COUNTER_FILES.size }
            ?: return cached ?: Triple(null, null, null)
        val counters = toCounters(parsed)
        val (rx, tx) = rates(name, counters)
        return Triple(counters, rx, tx).also { rootReads[name] = it }
    }

    private fun toCounters(resolved: List<Long>): TrafficCounters {
        return TrafficCounters(
            rxBytes = resolved[0], txBytes = resolved[1],
            rxPackets = resolved[2], txPackets = resolved[3],
            rxErrors = resolved[4], txErrors = resolved[5],
            rxDropped = resolved[6], txDropped = resolved[7],
            timestampMs = System.currentTimeMillis(),
        )
    }

    private fun rates(name: String, current: TrafficCounters?): Pair<Double?, Double?> {
        if (current == null) return null to null
        val previous = previousCounters.put(name, current) ?: return null to null
        val seconds = (current.timestampMs - previous.timestampMs) / 1000.0
        if (seconds <= 0.2) return null to null
        val rx = (current.rxBytes - previous.rxBytes).coerceAtLeast(0) / seconds
        val tx = (current.txBytes - previous.txBytes).coerceAtLeast(0) / seconds
        return rx to tx
    }

    private companion object {
        const val ROOT_READ_INTERVAL_MS = 6_000L
        val COUNTER_FILES = listOf(
            "rx_bytes", "tx_bytes", "rx_packets", "tx_packets",
            "rx_errors", "tx_errors", "rx_dropped", "tx_dropped",
        )
    }
}
