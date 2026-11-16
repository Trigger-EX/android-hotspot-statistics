package com.triggerex.hotspotstats.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientMergerTest {

    private val now = 1_000_000L

    private fun inputs(
        previous: List<ConnectedClient> = emptyList(),
        probes: List<ClientScanner.ProbeResult> = emptyList(),
        neighbours: List<NeighbourEntry> = emptyList(),
        stations: List<StationInfo>? = null,
        leases: List<Parsers.Lease> = emptyList(),
        names: Map<String, Pair<String, String>> = emptyMap(),
        nowMs: Long = now,
    ) = ClientMerger.Inputs(
        previous = previous.associateBy { it.key },
        probes = probes,
        neighbours = neighbours,
        stations = stations,
        leases = leases,
        names = names,
        ownIp = "192.168.43.1",
        nowMs = nowMs,
    )

    private fun station(mac: String, signal: Int = -50) = StationInfo(
        mac, signal, null, null, null, null, null, null, null, null, null, null, null, null,
    )

    private fun neighbour(ip: String, mac: String) = NeighbourEntry(ip, mac, "wlan1", "REACHABLE", Source.ARP)

    @Test
    fun `combines probe neighbour station and name`() {
        val clients = ClientMerger.merge(
            inputs(
                probes = listOf(ClientScanner.ProbeResult("192.168.43.10", 4.2, listOf(62078))),
                neighbours = listOf(neighbour("192.168.43.10", "aa:bb:cc:dd:ee:01")),
                stations = listOf(station("aa:bb:cc:dd:ee:01", -48)),
                names = mapOf("192.168.43.10" to ("Johns-iPhone.local" to "mDNS")),
            ),
        )
        assertEquals(1, clients.size)
        val c = clients.single()
        assertEquals("aa:bb:cc:dd:ee:01", c.mac)
        assertEquals("Johns-iPhone.local", c.hostname)
        assertEquals(-48, c.station?.signalDbm)
        assertEquals(4.2, c.latencyMs!!, 0.0)
        assertEquals(setOf(Source.SUBNET_SCAN, Source.ARP, Source.IW), c.sources)
        assertEquals("Apple iOS/iPadOS device", c.deviceHint)
    }

    @Test
    fun `excludes own address`() {
        val clients = ClientMerger.merge(
            inputs(probes = listOf(ClientScanner.ProbeResult("192.168.43.1", 0.1, emptyList()))),
        )
        assertTrue(clients.isEmpty())
    }

    @Test
    fun `iw is authoritative over stale arp entries`() {
        val clients = ClientMerger.merge(
            inputs(
                neighbours = listOf(
                    neighbour("192.168.43.10", "aa:bb:cc:dd:ee:01"),
                    neighbour("192.168.43.11", "aa:bb:cc:dd:ee:02"),
                ),
                stations = listOf(station("aa:bb:cc:dd:ee:01")),
            ),
        )
        assertEquals(listOf("192.168.43.10"), clients.map { it.ip })
    }

    @Test
    fun `station without ip is listed and later mapped by lease`() {
        val first = ClientMerger.merge(inputs(stations = listOf(station("aa:bb:cc:dd:ee:09"))))
        assertEquals(1, first.size)
        assertNull(first.single().ip)

        val second = ClientMerger.merge(
            inputs(
                previous = first,
                stations = listOf(station("aa:bb:cc:dd:ee:09")),
                leases = listOf(Parsers.Lease("aa:bb:cc:dd:ee:09", "192.168.43.50", "laptop", null)),
                nowMs = now + 5_000,
            ),
        )
        assertEquals("192.168.43.50", second.single().ip)
        assertEquals("laptop", second.single().hostname)
        assertEquals(now, second.single().firstSeenMs)
    }

    @Test
    fun `keeps missed clients during grace period then drops them`() {
        val seen = ClientMerger.merge(
            inputs(probes = listOf(ClientScanner.ProbeResult("192.168.43.10", 3.0, emptyList()))),
        )
        val withinGrace = ClientMerger.merge(inputs(previous = seen, nowMs = now + 30_000))
        assertEquals(1, withinGrace.size)
        assertEquals(now, withinGrace.single().lastSeenMs)

        val afterGrace = ClientMerger.merge(inputs(previous = seen, nowMs = now + 120_000))
        assertTrue(afterGrace.isEmpty())
    }

    @Test
    fun `preserves first seen and hostname across passes`() {
        val first = ClientMerger.merge(
            inputs(
                probes = listOf(ClientScanner.ProbeResult("192.168.43.10", 3.0, emptyList())),
                names = mapOf("192.168.43.10" to ("DESKTOP-ABC" to "NetBIOS")),
            ),
        )
        val second = ClientMerger.merge(
            inputs(
                previous = first,
                probes = listOf(ClientScanner.ProbeResult("192.168.43.10", 9.0, emptyList())),
                nowMs = now + 10_000,
            ),
        )
        val c = second.single()
        assertEquals(now, c.firstSeenMs)
        assertEquals(now + 10_000, c.lastSeenMs)
        assertEquals("DESKTOP-ABC", c.hostname)
        assertEquals(9.0, c.latencyMs!!, 0.0)
    }

    @Test
    fun `sorts numerically by ip`() {
        val clients = ClientMerger.merge(
            inputs(
                probes = listOf("192.168.43.100", "192.168.43.9", "192.168.43.20").map {
                    ClientScanner.ProbeResult(it, 1.0, emptyList())
                },
            ),
        )
        assertEquals(listOf("192.168.43.9", "192.168.43.20", "192.168.43.100"), clients.map { it.ip })
    }
}
