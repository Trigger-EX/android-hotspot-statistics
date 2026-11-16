package com.triggerex.hotspotstats.data

/**
 * Combines every discovery source into one client list. Pure, so it is unit tested.
 *
 * Rules:
 * - A client is present if a probe answered, the neighbour table lists it, or `iw` lists its MAC.
 * - When `iw` data exists it is authoritative for association: a neighbour entry whose MAC
 *   is not an associated station is a leftover and is ignored.
 * - A client not seen in this pass is kept for [graceMs] after it was last seen, because
 *   sleeping phones often skip a probe.
 */
object ClientMerger {

    data class Inputs(
        val previous: Map<String, ConnectedClient>,
        val probes: List<ClientScanner.ProbeResult>,
        val neighbours: List<NeighbourEntry>,
        /** Null when `iw` is unavailable, empty when it ran and found no stations. */
        val stations: List<StationInfo>?,
        val leases: List<Parsers.Lease>,
        val names: Map<String, Pair<String, String>>,
        val ownIp: String?,
        val nowMs: Long,
        val graceMs: Long = 90_000,
    )

    fun merge(input: Inputs): List<ConnectedClient> {
        val stationsByMac = input.stations?.associateBy { it.mac }.orEmpty()
        val leaseByIp = input.leases.associateBy { it.ip }
        val leaseByMac = input.leases.associateBy { it.mac }
        val probeByIp = input.probes.associateBy { it.ip }

        val neighbours = input.neighbours.filter { n ->
            n.ip != input.ownIp && (input.stations == null || n.mac in stationsByMac)
        }
        val neighbourByIp = neighbours.associateBy { it.ip }

        val seenIps = (probeByIp.keys + neighbourByIp.keys) - setOfNotNull(input.ownIp)
        val result = mutableMapOf<String, ConnectedClient>()

        for (ip in seenIps) {
            val probe = probeByIp[ip]
            val neighbour = neighbourByIp[ip]
            val knownMac = neighbour?.mac ?: leaseByIp[ip]?.mac
            // An IP handed to a different device (different MAC) must not inherit the old identity.
            val previous = input.previous[ip]?.takeUnless { knownMac != null && it.mac != null && it.mac != knownMac }
                ?: knownMac?.let { m -> input.previous.values.firstOrNull { it.mac == m } }
            val mac = knownMac ?: previous?.mac
            val station = mac?.let(stationsByMac::get)
            val sources = buildSet {
                if (probe != null) add(Source.SUBNET_SCAN)
                neighbour?.let { add(it.source) }
                if (station != null) add(Source.IW)
            }
            result[ip] = build(ip, mac, probe, neighbour, station, previous, input, sources)
        }

        // Associated stations whose IP we could not map yet.
        val mappedMacs = result.values.mapNotNull { it.mac }.toSet()
        for (station in input.stations.orEmpty()) {
            if (station.mac in mappedMacs) continue
            val leaseIp = leaseByMac[station.mac]?.ip?.takeIf { it !in result }
            val key = leaseIp ?: station.mac
            val previous = input.previous[key] ?: input.previous.values.firstOrNull { it.mac == station.mac }
            result[key] = build(leaseIp, station.mac, null, null, station, previous, input, setOf(Source.IW))
        }

        // Grace period for clients that skipped this pass.
        for ((key, old) in input.previous) {
            if (key in result) continue
            if (old.mac != null && result.values.any { it.mac == old.mac }) continue
            if (input.stations != null && old.mac != null && old.mac !in stationsByMac) continue
            if (input.nowMs - old.lastSeenMs <= input.graceMs) result[key] = old
        }

        return result.values.sortedWith(compareBy({ it.ip == null }, { it.ip?.let(Parsers::ipv4ToInt) ?: 0 }, { it.mac }))
    }

    private fun build(
        ip: String?,
        mac: String?,
        probe: ClientScanner.ProbeResult?,
        neighbour: NeighbourEntry?,
        station: StationInfo?,
        previous: ConnectedClient?,
        input: Inputs,
        sources: Set<Source>,
    ): ConnectedClient {
        val lease = ip?.let { input.leases.firstOrNull { l -> l.ip == it } } ?: mac?.let { input.leases.firstOrNull { l -> l.mac == it } }
        val resolved = ip?.let(input.names::get)
        val (hostname, hostnameSource) = when {
            lease?.hostname != null -> lease.hostname to "DHCP lease"
            resolved != null -> resolved
            else -> previous?.hostname to previous?.hostnameSource
        }
        // Fresh probe results replace older ones; quick passes carry no port data, so keep the last set.
        val openPorts = probe?.openPorts?.takeIf { it.isNotEmpty() } ?: previous?.openPorts.orEmpty()
        return ConnectedClient(
            ip = ip,
            mac = mac,
            hostname = hostname,
            hostnameSource = hostnameSource,
            deviceHint = Parsers.deviceHint(openPorts, hostname),
            isRandomizedMac = Parsers.isRandomizedMac(mac),
            latencyMs = probe?.latencyMs ?: previous?.latencyMs,
            openPorts = openPorts,
            neighbourState = neighbour?.state,
            station = station,
            sources = sources,
            firstSeenMs = previous?.firstSeenMs ?: input.nowMs,
            lastSeenMs = input.nowMs,
        )
    }
}
