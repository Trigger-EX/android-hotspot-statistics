package com.triggerex.hotspotstats.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Finds devices connected to the hotspot.
 *
 * Normal apps cannot ask Android for the hotspot's client list, so this combines:
 * an active sweep of the hotspot subnet (ICMP echo plus TCP connects, where a refused
 * connection still proves the host exists), the kernel neighbour table where readable,
 * and with root, `iw station dump` and DHCP leases.
 */
class ClientScanner(private val shell: ShellRunner) {

    data class ProbeResult(val ip: String, val latencyMs: Double?, val openPorts: List<Int>)

    data class NeighbourRead(val entries: List<NeighbourEntry>, val notes: List<String>)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val probeDispatcher = Dispatchers.IO.limitedParallelism(PROBE_THREADS)

    // ------------------------------------------------------------------ sweep

    suspend fun sweep(
        hotspot: InterfaceInfo,
        onProgress: (probed: Int, total: Int) -> Unit,
    ): List<ProbeResult> {
        val ipv4 = hotspot.ipv4 ?: return emptyList()
        val hosts = Parsers.subnetHosts(ipv4.address, ipv4.prefixLength)
        val done = AtomicInteger(0)
        val gate = Semaphore(HOST_CONCURRENCY)
        onProgress(0, hosts.size)
        return coroutineScope {
            hosts.map { ip ->
                async(probeDispatcher) {
                    gate.withPermit {
                        probeHost(ip, SWEEP_PORTS).also { onProgress(done.incrementAndGet(), hosts.size) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Probes one host: ICMP echo and TCP connects in parallel. Null when nothing answered. */
    suspend fun probeHost(ip: String, ports: Collection<Int>): ProbeResult? = coroutineScope {
        val icmp = async(probeDispatcher) { icmpRtt(ip, ICMP_TIMEOUT_MS) }
        val tcp = ports.map { port -> async(probeDispatcher) { port to tcpProbe(ip, port, TCP_TIMEOUT_MS) } }
        val icmpRtt = icmp.await()
        val tcpResults = tcp.awaitAll()
        val answered = tcpResults.filter { it.second != null }
        if (icmpRtt == null && answered.isEmpty()) return@coroutineScope null
        ProbeResult(
            ip = ip,
            latencyMs = icmpRtt ?: answered.minOfOrNull { it.second!!.rttMs },
            openPorts = answered.filter { it.second!!.open }.map { it.first }.sorted(),
        )
    }

    /** Follow-up on a host that is known to be alive: full port list for device hints. */
    suspend fun fingerprint(ip: String): ProbeResult? = probeHost(ip, Parsers.HINT_PORTS.keys)

    /** Average ICMP round trip over a few samples, or null if the host does not echo. */
    suspend fun latency(ip: String, samples: Int = 3): Double? = withContext(probeDispatcher) {
        val rtts = (1..samples).mapNotNull { icmpRtt(ip, ICMP_TIMEOUT_MS) }
        if (rtts.isEmpty()) null else rtts.average()
    }

    private fun icmpRtt(ip: String, timeoutMs: Int): Double? = try {
        val addr = InetAddress.getByName(ip)
        val start = System.nanoTime()
        if (addr.isReachable(timeoutMs)) (System.nanoTime() - start) / 1e6 else null
    } catch (_: Exception) {
        null
    }

    private class TcpAnswer(val open: Boolean, val rttMs: Double)

    /** Open or refused both prove a host exists; timeouts and unreachable do not. */
    private fun tcpProbe(ip: String, port: Int, timeoutMs: Int): TcpAnswer? {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            TcpAnswer(true, (System.nanoTime() - start) / 1e6)
        } catch (e: ConnectException) {
            val msg = e.message.orEmpty()
            if ("ECONNREFUSED" in msg || "refused" in msg.lowercase()) {
                TcpAnswer(false, (System.nanoTime() - start) / 1e6)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ kernel tables & root

    suspend fun readNeighbours(iface: String?): NeighbourRead {
        val notes = mutableListOf<String>()
        val entries = mutableListOf<NeighbourEntry>()

        val arp = shell.readFile("/proc/net/arp", root = false)
        if (arp != null) {
            entries += Parsers.parseProcNetArp(arp)
            notes += "ARP table readable without root."
        }
        val neigh = shell.run("ip neigh show", root = false)
        if (neigh != null && neigh.ok && neigh.stdout.isNotBlank()) {
            entries += Parsers.parseIpNeigh(neigh.stdout)
            notes += "Neighbour table readable without root."
        }
        if (entries.isEmpty() && shell.rootGranted) {
            shell.readFile("/proc/net/arp", root = true)?.let { entries += Parsers.parseProcNetArp(it) }
            shell.run("ip neigh show", root = true)?.takeIf { it.ok }?.let { entries += Parsers.parseIpNeigh(it.stdout) }
            notes += "Neighbour table read with root."
        }
        if (entries.isEmpty() && notes.isEmpty()) {
            notes += "ARP / neighbour table is blocked on this Android version without root, " +
                "so MAC addresses are unavailable and silent devices may be missed."
        }

        val filtered = entries
            .filter { iface == null || it.device == null || it.device == iface }
            .distinctBy { it.ip }
        return NeighbourRead(filtered, notes)
    }

    suspend fun isIwAvailable(): Boolean =
        shell.rootGranted && shell.run("which iw || ls /system/bin/iw /vendor/bin/iw", root = true)
            ?.stdout?.contains("iw") == true

    /** Per-station radio details. Null when root or `iw` is unavailable. */
    suspend fun readStations(iface: String): List<StationInfo>? {
        if (!shell.rootGranted) return null
        val result = shell.run("iw dev $iface station dump", root = true) ?: return null
        if (!result.ok) return null
        return Parsers.parseIwStationDump(result.stdout)
    }

    /** DHCP leases kept by dnsmasq on Android 10 and earlier. */
    suspend fun readLeases(): List<Parsers.Lease> {
        if (!shell.rootGranted) return emptyList()
        return LEASE_PATHS.firstNotNullOfOrNull { path -> shell.readFile(path, root = true) }
            ?.let(Parsers::parseDnsmasqLeases)
            .orEmpty()
    }

    // ------------------------------------------------------------------ names

    /** Tries mDNS (Apple, Linux, many IoT) and then NetBIOS (Windows, Samba). */
    suspend fun resolveName(ip: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        mdnsReverse(ip)?.let { return@withContext it to "mDNS" }
        netbiosName(ip)?.let { return@withContext it to "NetBIOS" }
        null
    }

    private fun mdnsReverse(ip: String): String? {
        val name = Parsers.reverseArpaName(ip) ?: return null
        val id = Random.nextInt(1, 0xffff)
        val response = udpExchange(ip, 5353, Parsers.buildPtrQuery(id, name)) ?: return null
        return Parsers.parsePtrResponse(response.first, response.second)
    }

    private fun netbiosName(ip: String): String? {
        val id = Random.nextInt(1, 0xffff)
        val response = udpExchange(ip, 137, Parsers.buildNetbiosStatusRequest(id)) ?: return null
        return Parsers.parseNetbiosStatusResponse(response.first, response.second)
    }

    private fun udpExchange(ip: String, port: Int, payload: ByteArray): Pair<ByteArray, Int>? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = NAME_TIMEOUT_MS
            val addr = InetAddress.getByName(ip)
            socket.send(DatagramPacket(payload, payload.size, addr, port))
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            buf to packet.length
        }
    } catch (_: SocketTimeoutException) {
        null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val HOST_CONCURRENCY = 32
        const val PROBE_THREADS = 192
        const val ICMP_TIMEOUT_MS = 700
        const val TCP_TIMEOUT_MS = 500
        const val NAME_TIMEOUT_MS = 600

        /** A small set that most phones and PCs either accept or actively refuse. */
        val SWEEP_PORTS = listOf(80, 443, 62078, 445)

        val LEASE_PATHS = listOf(
            "/data/misc/dhcp/dnsmasq.leases",
            "/data/misc/dhcp/dnsmasq.leases.tmp",
        )
    }
}
