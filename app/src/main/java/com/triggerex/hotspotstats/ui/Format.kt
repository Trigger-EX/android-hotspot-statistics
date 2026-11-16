package com.triggerex.hotspotstats.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = value / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024; i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    /** Bytes per second shown as bits per second, the way link speeds are usually quoted. */
    fun rate(bytesPerSecond: Double?): String {
        if (bytesPerSecond == null) return "—"
        val bits = bytesPerSecond * 8
        return when {
            bits >= 1e9 -> String.format(Locale.US, "%.2f Gbit/s", bits / 1e9)
            bits >= 1e6 -> String.format(Locale.US, "%.1f Mbit/s", bits / 1e6)
            bits >= 1e3 -> String.format(Locale.US, "%.0f kbit/s", bits / 1e3)
            else -> String.format(Locale.US, "%.0f bit/s", bits)
        }
    }

    fun kbps(value: Int?): String = when {
        value == null -> "—"
        value >= 1_000_000 -> String.format(Locale.US, "%.1f Gbit/s", value / 1e6)
        value >= 1_000 -> String.format(Locale.US, "%.1f Mbit/s", value / 1e3)
        else -> "$value kbit/s"
    }

    fun duration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "—"
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun ago(timestampMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs == null) return "never"
        val s = (nowMs - timestampMs) / 1000
        return if (s < 3) "just now" else "${duration(s)} ago"
    }

    fun time(timestampMs: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestampMs))

    fun ms(value: Double?): String = when {
        value == null -> "—"
        value < 10 -> String.format(Locale.US, "%.1f ms", value)
        else -> String.format(Locale.US, "%.0f ms", value)
    }

    fun mbps(value: Double?): String = value?.let { String.format(Locale.US, "%.1f Mbit/s", it) } ?: "—"

    fun yesNo(value: Boolean?): String = when (value) {
        true -> "Yes"
        false -> "No"
        null -> "—"
    }

    /** 0..4 bars from a Wi-Fi RSSI, matching Android's own thresholds closely. */
    fun wifiLevel(dbm: Int): Int = when {
        dbm >= -55 -> 4
        dbm >= -66 -> 3
        dbm >= -77 -> 2
        dbm >= -88 -> 1
        else -> 0
    }

    fun wifiQuality(dbm: Int): String = when (wifiLevel(dbm)) {
        4 -> "Excellent"
        3 -> "Good"
        2 -> "Fair"
        1 -> "Weak"
        else -> "Very weak"
    }
}
