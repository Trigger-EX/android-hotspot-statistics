package com.triggerex.hotspotstats.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure text / byte parsers. Kept free of Android classes so they run in JVM unit tests.
 */
object Parsers {

    private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")
    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    // ---------------------------------------------------------------- MAC helpers

    fun normalizeMac(raw: String?): String? {
        val mac = raw?.trim()?.lowercase()?.replace('-', ':') ?: return null
        if (!MAC_REGEX.matches(mac)) return null
        if (mac == "00:00:00:00:00:00" || mac == "ff:ff:ff:ff:ff:ff") return null
        return mac
    }

    private fun firstOctet(mac: String): Int? = mac.substringBefore(':').toIntOrNull(16)

    /** Locally administered bit set: almost always a privacy-randomised MAC on phones. */
    fun isRandomizedMac(mac: String?): Boolean {
        val octet = normalizeMac(mac)?.let(::firstOctet) ?: return false
        return octet and 0x02 != 0
    }

    // ---------------------------------------------------------------- /proc/net/arp

    /**
     * Format:
     * ```
     * IP address       HW type     Flags       HW address            Mask     Device
     * 192.168.43.12    0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan1
     * ```
     * Flags 0x0 means the entry is incomplete (host did not answer ARP).
     */
    fun parseProcNetArp(text: String): List<NeighbourEntry> =
        text.lineSequence()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 6 && IPV4_REGEX.matches(it[0]) }
            .mapNotNull { cols ->
                val flags = cols[2].removePrefix("0x").toIntOrNull(16) ?: 0
                val mac = normalizeMac(cols[3])
                if (flags and 0x2 == 0 || mac == null) return@mapNotNull null
                NeighbourEntry(
                    ip = cols[0],
                    mac = mac,
                    device = cols[5],
                    state = if (flags and 0x4 != 0) "PERMANENT" else "REACHABLE",
                    source = Source.ARP,
                )
            }
            .toList()

    // ---------------------------------------------------------------- ip neigh

    /**
     * Format examples:
     * ```
     * 192.168.43.12 dev wlan1 lladdr aa:bb:cc:dd:ee:ff REACHABLE
     * fe80::1c2b dev wlan1 lladdr aa:bb:cc:dd:ee:ff router STALE
     * 192.168.43.9 dev wlan1  FAILED
     * ```
     */
    fun parseIpNeigh(text: String): List<NeighbourEntry> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val tokens = line.split(Regex("\\s+"))
                val ip = tokens.firstOrNull() ?: return@mapNotNull null
                if (!IPV4_REGEX.matches(ip)) return@mapNotNull null
                val dev = tokens.valueAfter("dev")
                val mac = normalizeMac(tokens.valueAfter("lladdr"))
                val state = tokens.last().takeIf { it.all { c -> c.isUpperCase() } }
                if (mac == null || state == "FAILED" || state == "INCOMPLETE") return@mapNotNull null
                NeighbourEntry(ip, mac, dev, state, Source.NEIGHBOUR)
            }
            .toList()

    private fun List<String>.valueAfter(key: String): String? =
        indexOf(key).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    // ---------------------------------------------------------------- dnsmasq leases (Android <= 10)

    data class Lease(val mac: String, val ip: String, val hostname: String?, val expiryEpochSec: Long?)

    /** `1700000000 aa:bb:cc:dd:ee:ff 192.168.43.12 Pixel-7 01:aa:bb:cc:dd:ee:ff` */
    fun parseDnsmasqLeases(text: String): List<Lease> =
        text.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 4 }
            .mapNotNull { cols ->
                val mac = normalizeMac(cols[1]) ?: return@mapNotNull null
                if (!IPV4_REGEX.matches(cols[2])) return@mapNotNull null
                Lease(mac, cols[2], cols[3].takeUnless { it == "*" }, cols[0].toLongOrNull())
            }
            .toList()

    // ---------------------------------------------------------------- iw station dump

    fun parseIwStationDump(text: String): List<StationInfo> {
        val result = mutableListOf<StationInfo>()
        var mac: String? = null
        val fields = mutableMapOf<String, String>()

        fun flush() {
            val m = mac ?: return
            result += StationInfo(
                mac = m,
                signalDbm = fields["signal"]?.leadingInt(),
                signalAvgDbm = fields["signal avg"]?.leadingInt(),
                txBitrateMbps = fields["tx bitrate"]?.leadingDouble(),
                rxBitrateMbps = fields["rx bitrate"]?.leadingDouble(),
                rxBytes = fields["rx bytes"]?.leadingLong(),
                txBytes = fields["tx bytes"]?.leadingLong(),
                rxPackets = fields["rx packets"]?.leadingLong(),
                txPackets = fields["tx packets"]?.leadingLong(),
                txRetries = fields["tx retries"]?.leadingLong(),
                txFailed = fields["tx failed"]?.leadingLong(),
                inactiveMs = fields["inactive time"]?.leadingLong(),
                connectedSeconds = fields["connected time"]?.leadingLong(),
                authorized = fields["authorized"]?.let { it.trim() == "yes" },
            )
            fields.clear()
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("Station ")) {
                flush()
                mac = normalizeMac(line.removePrefix("Station ").substringBefore(' '))
            } else if (mac != null && ':' in line) {
                val key = line.substringBefore(':').trim()
                fields[key] = line.substringAfter(':').trim()
            }
        }
        flush()
        return result
    }

    private val INT_PREFIX = Regex("^-?\\d+")
    private val DOUBLE_PREFIX = Regex("^-?\\d+(\\.\\d+)?")
    private fun String.leadingInt() = INT_PREFIX.find(trim())?.value?.toIntOrNull()
    private fun String.leadingLong() = INT_PREFIX.find(trim())?.value?.toLongOrNull()
    private fun String.leadingDouble() = DOUBLE_PREFIX.find(trim())?.value?.toDoubleOrNull()

    // ---------------------------------------------------------------- soft-AP config XML (Android 11+)

    /**
     * Parses `WifiConfigStoreSoftAp.xml`. Field names differ by Android version
     * (`SSID` on 11-12, `WifiSsid` on 13+), so this matches by name rather than position.
     */
    fun parseSoftApXml(xml: String): HotspotConfig? {
        if (!xml.contains("<SoftAp")) return null

        fun str(name: String): String? =
            Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.let(::xmlUnescape)

        fun int(name: String): Int? =
            Regex("<int name=\"$name\" value=\"(-?\\d+)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull()

        fun bool(name: String): Boolean? =
            Regex("<boolean name=\"$name\" value=\"(true|false)\"").find(xml)?.groupValues?.get(1)?.toBoolean()

        val ssid = str("WifiSsid")?.let(::decodeWifiSsid) ?: str("SSID")
        val passphrase = str("Passphrase")
            ?: if (xml.contains("EncryptedData")) "(stored encrypted)" else null

        return HotspotConfig(
            ssid = ssid,
            bssid = str("Bssid"),
            passphrase = passphrase,
            security = int("SecurityType")?.let(::securityTypeName),
            band = int("ApBand")?.let(::bandName),
            channel = int("Channel")?.takeIf { it > 0 },
            hidden = bool("HiddenSSID"),
            maxClients = int("MaxNumberOfClients")?.takeIf { it > 0 },
            autoShutdown = bool("AutoShutdownEnabled"),
            source = Source.SOFTAP_CONFIG,
        )
    }

    /** `"MyHotspot"` is UTF-8 text; an unquoted value is hex-encoded bytes. */
    fun decodeWifiSsid(value: String): String {
        val v = value.trim()
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) return v.substring(1, v.length - 1)
        if (v.isNotEmpty() && v.length % 2 == 0 && v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val bytes = v.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return bytes.toString(Charsets.UTF_8)
        }
        return v
    }

    fun xmlUnescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /** `SoftApConfiguration.SECURITY_TYPE_*` values. */
    fun securityTypeName(type: Int): String = when (type) {
        0 -> "Open"
        1 -> "WPA2-Personal"
        2 -> "WPA3/WPA2 transition"
        3 -> "WPA3-Personal (SAE)"
        4 -> "Enhanced Open transition (OWE)"
        5 -> "Enhanced Open (OWE)"
        else -> "Unknown ($type)"
    }

    /** `SoftApConfiguration.BAND_*` bit mask. */
    fun bandName(mask: Int): String {
        val parts = buildList {
            if (mask and 1 != 0) add("2.4 GHz")
            if (mask and 2 != 0) add("5 GHz")
            if (mask and 4 != 0) add("6 GHz")
            if (mask and 8 != 0) add("60 GHz")
        }
        return if (parts.isEmpty()) "Unknown ($mask)" else parts.joinToString(" + ")
    }

    // ---------------------------------------------------------------- sysfs / ping

    fun parseSysfsLong(text: String?): Long? = text?.trim()?.toLongOrNull()

    fun channelFromFrequency(mhz: Int): Int? = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5160..5885 -> (mhz - 5000) / 5
        in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }

    // ---------------------------------------------------------------- subnet maths

    fun ipv4ToInt(ip: String): Int? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    fun intToIpv4(value: Int): String =
        "${value ushr 24 and 0xff}.${value ushr 16 and 0xff}.${value ushr 8 and 0xff}.${value and 0xff}"

    /**
     * All host addresses in [ownIp]/[prefix], minus network, broadcast and [ownIp].
     * Subnets larger than /[maxPrefix] are narrowed to the /[maxPrefix] around [ownIp]
     * so a sweep stays bounded.
     */
    fun subnetHosts(ownIp: String, prefix: Int, maxPrefix: Int = 22): List<String> {
        val own = ipv4ToInt(ownIp) ?: return emptyList()
        val effective = prefix.coerceIn(maxPrefix, 30)
        val mask = if (effective == 0) 0 else -1 shl (32 - effective)
        val network = own and mask
        val broadcast = network or mask.inv()
        val result = ArrayList<String>()
        var addr = network + 1
        while (addr < broadcast) {
            if (addr != own) result += intToIpv4(addr)
            addr++
        }
        return result
    }

    fun isPrivateIpv4(ip: String): Boolean {
        val v = ipv4ToInt(ip) ?: return false
        val a = v ushr 24 and 0xff
        val b = v ushr 16 and 0xff
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    // ---------------------------------------------------------------- NetBIOS node status

    /** NBSTAT query for the wildcard name `*`, as sent to UDP 137. */
    fun buildNetbiosStatusRequest(transactionId: Int): ByteArray {
        val buf = ByteBuffer.allocate(50).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(transactionId.toShort())
        buf.putShort(0) // flags
        buf.putShort(1) // questions
        buf.putShort(0); buf.putShort(0); buf.putShort(0)
        buf.put(0x20)
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (b in name) {
            val v = b.toInt() and 0xff
            buf.put(('A'.code + (v shr 4)).toByte())
            buf.put(('A'.code + (v and 0x0f)).toByte())
        }
        buf.put(0)
        buf.putShort(0x21) // NBSTAT
        buf.putShort(0x01) // IN
        return buf.array()
    }

    /** Returns the unique workstation name (suffix 0x00) from an NBSTAT response. */
    fun parseNetbiosStatusResponse(data: ByteArray, length: Int = data.size): String? {
        if (length < 57) return null
        var pos = skipDnsName(data, 12, length) ?: return null
        pos += 2 + 2 + 4 + 2 // type, class, ttl, rdlength
        if (pos >= length) return null
        val count = data[pos].toInt() and 0xff
        pos++
        repeat(count) {
            if (pos + 18 > length) return null
            val name = String(data, pos, 15, Charsets.US_ASCII).trim { it <= ' ' }
            val suffix = data[pos + 15].toInt() and 0xff
            val flags = ((data[pos + 16].toInt() and 0xff) shl 8) or (data[pos + 17].toInt() and 0xff)
            val isGroup = flags and 0x8000 != 0 // group names are workgroups, not hosts
            if (suffix == 0x00 && name.isNotEmpty() && !isGroup) return name
            pos += 18
        }
        return null
    }

    // ---------------------------------------------------------------- DNS / mDNS reverse lookup

    fun reverseArpaName(ipv4: String): String? {
        val parts = ipv4.split('.')
        if (parts.size != 4) return null
        return parts.reversed().joinToString(".") + ".in-addr.arpa"
    }

    /** A standard DNS PTR query, used as an mDNS "legacy unicast" query to UDP 5353. */
    fun buildPtrQuery(transactionId: Int, name: String): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(transactionId.toShort())
        buf.putShort(0)
        buf.putShort(1)
        buf.putShort(0); buf.putShort(0); buf.putShort(0)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buf.put(bytes.size.toByte())
            buf.put(bytes)
        }
        buf.put(0)
        buf.putShort(12) // PTR
        buf.putShort(1) // IN
        return buf.array()
    }

    /** Returns the target of the first PTR answer, without a trailing dot. */
    fun parsePtrResponse(data: ByteArray, length: Int = data.size): String? {
        if (length < 12) return null
        val qd = u16(data, 4)
        val an = u16(data, 6)
        if (an == 0) return null
        var pos = 12
        repeat(qd) {
            pos = skipDnsName(data, pos, length) ?: return null
            pos += 4
        }
        repeat(an) {
            pos = skipDnsName(data, pos, length) ?: return null
            if (pos + 10 > length) return null
            val type = u16(data, pos)
            val rdLength = u16(data, pos + 8)
            val rdata = pos + 10
            if (type == 12) return readDnsName(data, rdata, length)?.trimEnd('.')
            pos = rdata + rdLength
        }
        return null
    }

    private fun u16(data: ByteArray, pos: Int) =
        ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)

    private fun skipDnsName(data: ByteArray, start: Int, length: Int): Int? {
        var pos = start
        while (pos < length) {
            val len = data[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xc0 == 0xc0 -> return pos + 2
                else -> pos += len + 1
            }
        }
        return null
    }

    private fun readDnsName(data: ByteArray, start: Int, length: Int): String? {
        val sb = StringBuilder()
        var pos = start
        var jumps = 0
        while (pos < length) {
            val len = data[pos].toInt() and 0xff
            when {
                len == 0 -> return sb.toString()
                len and 0xc0 == 0xc0 -> {
                    if (pos + 1 >= length || ++jumps > 16) return null
                    pos = ((len and 0x3f) shl 8) or (data[pos + 1].toInt() and 0xff)
                }
                else -> {
                    if (pos + 1 + len > length) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(data, pos + 1, len, Charsets.UTF_8))
                    pos += len + 1
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- device hints

    /** Well-known TCP ports whose presence hints at what a client is. */
    val HINT_PORTS: Map<Int, String> = linkedMapOf(
        62078 to "Apple iOS/iPadOS device",
        7000 to "Apple AirPlay receiver",
        8009 to "Google Cast device",
        5555 to "Android (ADB over network)",
        445 to "Windows / SMB file sharing",
        139 to "NetBIOS / SMB",
        22 to "SSH server (Linux / macOS)",
        3389 to "Windows Remote Desktop",
        80 to "Web server",
        443 to "HTTPS server",
        8080 to "Web server (alt)",
    )

    fun deviceHint(openPorts: Collection<Int>, hostname: String?): String? {
        val host = hostname?.lowercase().orEmpty()
        return when {
            "iphone" in host || "ipad" in host -> "Apple iOS/iPadOS device"
            "macbook" in host || "imac" in host || host.endsWith(".local") && "mac" in host -> "Apple Mac"
            "android" in host || "galaxy" in host || "pixel" in host -> "Android device"
            "desktop-" in host || "laptop-" in host -> "Windows PC"
            else -> HINT_PORTS.entries.firstOrNull { it.key in openPorts }?.value
        }
    }
}
