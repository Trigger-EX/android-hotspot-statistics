package com.triggerex.hotspotstats.capture

import com.triggerex.hotspotstats.data.Parsers

/** A device to capture, by IP and/or MAC. Matching either one keeps a packet. */
data class CaptureHost(val ip: String?, val mac: String?, val label: String)

data class CaptureRequest(
    val iface: String,
    /** Empty means all traffic on the hotspot interface. */
    val hosts: List<CaptureHost>,
    val durationSec: Int,
    val maxBytes: Long,
    /** 65535 for full packets; 128 keeps headers only. */
    val snaplen: Int,
)

/** One line of the helper's stderr protocol (see hscapture.c). */
sealed interface CaptureEvent {
    data class Started(val linktype: Int, val iface: String) : CaptureEvent
    data class Stats(val elapsedMs: Long, val packets: Long, val bytes: Long, val drops: Long, val seen: Long) : CaptureEvent
    data class Packet(val epochMs: Long, val length: Int, val summary: String) : CaptureEvent
    data class Error(val message: String) : CaptureEvent
    data class Ended(val reason: String, val packets: Long, val bytes: Long, val drops: Long) : CaptureEvent
}

object CaptureProtocol {

    private val IFACE = Regex("^[A-Za-z0-9_.:-]{1,15}$")

    /**
     * Builds the shell command passed to `su -c`. Every value is validated, so nothing from
     * the network (hostnames, etc.) can reach the shell.
     */
    fun command(binaryPath: String, request: CaptureRequest): String {
        require(IFACE.matches(request.iface)) { "Invalid interface name" }
        require(request.hosts.size <= 64) { "At most 64 hosts" }
        val args = mutableListOf(shellQuote(binaryPath), "-i", request.iface)
        for (host in request.hosts) {
            host.ip?.let {
                require(Parsers.ipv4ToInt(it) != null) { "Invalid IPv4 address: $it" }
                args += listOf("-a", it)
            }
            host.mac?.let {
                val mac = requireNotNull(Parsers.normalizeMac(it)) { "Invalid MAC address: $it" }
                args += listOf("-m", mac)
            }
            require(host.ip != null || host.mac != null) { "Host ${host.label} has no address" }
        }
        args += listOf(
            "-s", request.snaplen.coerceIn(64, 65535).toString(),
            "-d", request.durationSec.coerceIn(1, 24 * 3600).toString(),
            "-C", request.maxBytes.coerceAtLeast(1024).toString(),
            "-r", "40",
        )
        return args.joinToString(" ")
    }

    fun parse(line: String): CaptureEvent? {
        if (line.length < 2 || line[1] != ' ') return null
        val rest = line.substring(2)
        return when (line[0]) {
            'I' -> rest.split(' ', limit = 2).takeIf { it.size == 2 }?.let {
                CaptureEvent.Started(it[0].toIntOrNull() ?: return null, it[1])
            }
            'S' -> rest.split(' ').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 5 }?.let {
                CaptureEvent.Stats(it[0], it[1], it[2], it[3], it[4])
            }
            'P' -> rest.split(' ', limit = 3).takeIf { it.size == 3 }?.let {
                CaptureEvent.Packet(it[0].toLongOrNull() ?: return null, it[1].toIntOrNull() ?: return null, it[2])
            }
            'E' -> CaptureEvent.Error(rest)
            'X' -> rest.split(' ').takeIf { it.size == 4 }?.let {
                CaptureEvent.Ended(
                    it[0],
                    it[1].toLongOrNull() ?: return null,
                    it[2].toLongOrNull() ?: return null,
                    it[3].toLongOrNull() ?: return null,
                )
            }
            else -> null
        }
    }

    fun endReasonText(reason: String): String = when (reason) {
        "stopped" -> "Stopped"
        "time-limit" -> "Time limit reached"
        "size-limit" -> "File size limit reached"
        "count-limit" -> "Packet limit reached"
        "output-closed" -> "Output closed"
        else -> reason
    }

    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
