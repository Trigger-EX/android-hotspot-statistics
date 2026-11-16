package com.triggerex.hotspotstats.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Describes the network the device itself uses — the connection the hotspot shares. */
class UpstreamReader(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val telephony: TelephonyManager? = appContext.getSystemService(TelephonyManager::class.java)

    /** Interface names carrying any network the device itself is connected to. */
    @Suppress("DEPRECATION")
    fun upstreamInterfaces(): Set<String> = try {
        connectivity.allNetworks.mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    fun read(): UpstreamInfo? {
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        val link = connectivity.getLinkProperties(network)

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }

        val signal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.signalStrength.takeIf { it != Int.MIN_VALUE }
        } else {
            null
        }

        val details = mutableListOf<Pair<String, String>>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifiDetails(network, caps)?.let(details::addAll)
        }

        val cellular = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || transport == "VPN") {
            readCellular()
        } else {
            null
        }

        return UpstreamInfo(
            transport = transport,
            interfaceName = link?.interfaceName,
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            downstreamKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 },
            signalStrength = signal,
            addresses = link?.linkAddresses?.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" }.orEmpty(),
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress,
            privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                link?.privateDnsServerName ?: if (link?.isPrivateDnsActive == true) "Automatic" else null
            } else {
                null
            },
            signalDetails = details,
            cellular = cellular,
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiDetails(network: Network, caps: NetworkCapabilities): List<Pair<String, String>>? {
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps.transportInfo as? WifiInfo
        } else {
            wifiManager.connectionInfo
        }
        info ?: return null
        return buildList {
            if (info.rssi > -127) {
                add("RSSI" to "${info.rssi} dBm")
                add("Signal level" to "${WifiManager.calculateSignalLevel(info.rssi, 5)} / 4")
            }
            if (info.linkSpeed > 0) add("Link speed" to "${info.linkSpeed} Mbps")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (info.txLinkSpeedMbps > 0) add("TX link speed" to "${info.txLinkSpeedMbps} Mbps")
                if (info.rxLinkSpeedMbps > 0) add("RX link speed" to "${info.rxLinkSpeedMbps} Mbps")
            }
            if (info.frequency > 0) {
                val ch = Parsers.channelFromFrequency(info.frequency)
                add("Frequency" to "${info.frequency} MHz" + (ch?.let { " (channel $it)" } ?: ""))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiStandardName(info.wifiStandard)?.let { add("Standard" to it) }
            }
            info.ssid?.takeIf { it != UNKNOWN_SSID && it.isNotBlank() }?.let { add("SSID" to it.trim('"')) }
        }
    }

    private fun wifiStandardName(standard: Int): String? = when (standard) {
        1 -> "802.11a/b/g (legacy)"
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> "Wi-Fi 6 (802.11ax)"
        7 -> "802.11ad"
        8 -> "Wi-Fi 7 (802.11be)"
        else -> null
    }

    @SuppressLint("MissingPermission")
    private fun readCellular(): CellularInfo? {
        val tm = telephony ?: return null
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return null

        val details = mutableListOf<Pair<String, String>>()
        var level: Int? = null
        var dbm: Int? = null
        var generation: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val strength = tm.signalStrength
                if (strength != null) {
                    level = strength.level
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val cells = strength.cellSignalStrengths
                        generation = generationFrom(cells)
                        cells.forEach { details += describe(it) }
                        dbm = cells.firstOrNull { it.dbm != Int.MAX_VALUE }?.dbm
                    }
                }
            } catch (_: Exception) {
                // Some OEM builds throw here; leave the fields empty.
            }
        }

        val hasPhoneState = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPhoneState) {
            try {
                networkTypeName(tm.dataNetworkType)?.let { generation = it }
            } catch (_: SecurityException) {
            }
        }

        return CellularInfo(
            operatorName = tm.networkOperatorName?.takeIf { it.isNotBlank() },
            networkGeneration = generation,
            signalLevel = level,
            signalDbm = dbm,
            signalDetails = details,
            roaming = runCatching { tm.isNetworkRoaming }.getOrNull(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun generationFrom(cells: List<CellSignalStrength>): String? = when {
        cells.any { it is CellSignalStrengthNr } && cells.any { it is CellSignalStrengthLte } -> "5G NSA (LTE + NR)"
        cells.any { it is CellSignalStrengthNr } -> "5G"
        cells.any { it is CellSignalStrengthLte } -> "4G LTE"
        cells.any { it is CellSignalStrengthWcdma || it is CellSignalStrengthTdscdma } -> "3G"
        cells.any { it is CellSignalStrengthCdma } -> "CDMA"
        cells.any { it is CellSignalStrengthGsm } -> "2G GSM"
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun describe(cell: CellSignalStrength): List<Pair<String, String>> {
        fun valid(v: Int) = v != Int.MAX_VALUE && v != Int.MIN_VALUE
        val out = mutableListOf<Pair<String, String>>()
        when (cell) {
            is CellSignalStrengthLte -> {
                if (valid(cell.rsrp)) out += "LTE RSRP" to "${cell.rsrp} dBm"
                if (valid(cell.rsrq)) out += "LTE RSRQ" to "${cell.rsrq} dB"
                if (valid(cell.rssnr)) out += "LTE RSSNR" to "${cell.rssnr / 10.0} dB"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && valid(cell.rssi)) out += "LTE RSSI" to "${cell.rssi} dBm"
                if (valid(cell.timingAdvance)) out += "LTE timing advance" to "${cell.timingAdvance}"
            }
            is CellSignalStrengthNr -> {
                if (valid(cell.ssRsrp)) out += "NR SS-RSRP" to "${cell.ssRsrp} dBm"
                if (valid(cell.ssRsrq)) out += "NR SS-RSRQ" to "${cell.ssRsrq} dB"
                if (valid(cell.ssSinr)) out += "NR SS-SINR" to "${cell.ssSinr} dB"
                if (valid(cell.csiRsrp)) out += "NR CSI-RSRP" to "${cell.csiRsrp} dBm"
            }
            is CellSignalStrengthWcdma -> {
                if (valid(cell.dbm)) out += "WCDMA RSCP" to "${cell.dbm} dBm"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && valid(cell.ecNo)) out += "WCDMA Ec/No" to "${cell.ecNo} dB"
            }
            is CellSignalStrengthGsm -> {
                if (valid(cell.dbm)) out += "GSM RSSI" to "${cell.dbm} dBm"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && valid(cell.bitErrorRate)) out += "GSM BER" to "${cell.bitErrorRate}"
            }
            else -> if (valid(cell.dbm)) out += cell.javaClass.simpleName.removePrefix("CellSignalStrength") to "${cell.dbm} dBm"
        }
        out += "${cellName(cell)} level" to "${cell.level} / 4"
        return out
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cellName(cell: CellSignalStrength) = when (cell) {
        is CellSignalStrengthLte -> "LTE"
        is CellSignalStrengthNr -> "NR"
        is CellSignalStrengthWcdma -> "WCDMA"
        is CellSignalStrengthGsm -> "GSM"
        is CellSignalStrengthCdma -> "CDMA"
        else -> "Cell"
    }

    private fun networkTypeName(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G SA (NR)"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (Wi-Fi calling)"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G TD-SCDMA"
        TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
        TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
        else -> "Type $type"
    }

    private companion object {
        /** Value of `WifiManager.UNKNOWN_SSID`, which is only public from API 30. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
