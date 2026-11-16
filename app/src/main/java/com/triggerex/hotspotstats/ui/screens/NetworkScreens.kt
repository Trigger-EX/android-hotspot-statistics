package com.triggerex.hotspotstats.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.triggerex.hotspotstats.data.InterfaceInfo
import com.triggerex.hotspotstats.data.InterfaceKind
import com.triggerex.hotspotstats.ui.Chip
import com.triggerex.hotspotstats.ui.Format
import com.triggerex.hotspotstats.ui.InfoRow
import com.triggerex.hotspotstats.ui.Note
import com.triggerex.hotspotstats.ui.SectionCard
import com.triggerex.hotspotstats.ui.SignalBars
import com.triggerex.hotspotstats.ui.UiState

@Composable
fun InterfacesScreen(state: UiState, modifier: Modifier = Modifier) {
    val interfaces = state.snapshot?.interfaces.orEmpty().filter { it.kind != InterfaceKind.LOOPBACK }
    val hotspotName = state.snapshot?.hotspotInterface?.name
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Note("All network interfaces on this device. The hotspot interface is marked; tethering interfaces come first.")
        }
        items(interfaces, key = { it.name }) { InterfaceCard(it, it.name == hotspotName) }
    }
}

@Composable
private fun InterfaceCard(iface: InterfaceInfo, isHotspot: Boolean) {
    val icon = when (iface.kind) {
        InterfaceKind.WIFI_HOTSPOT, InterfaceKind.WIFI, InterfaceKind.WIFI_DIRECT -> Icons.Filled.Wifi
        InterfaceKind.CELLULAR -> Icons.Filled.CellTower
        InterfaceKind.USB_TETHER, InterfaceKind.ETHERNET -> Icons.Filled.Cable
        else -> Icons.Filled.NetworkCheck
    }
    SectionCard(
        title = iface.name,
        icon = icon,
        trailing = { Chip(if (iface.isUp) "UP" else "DOWN") },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(iface.kind.label)
            if (isHotspot) Chip("Hotspot", MaterialTheme.colorScheme.primaryContainer)
            if (iface.tethered) Chip("Tethered")
            if (iface.isUpstream) Chip("Upstream")
        }
        iface.addresses.forEach { a ->
            InfoRow(if (a.isIpv6) "IPv6 (${a.scope})" else "IPv4 (${a.scope})", "${a.address}/${a.prefixLength}", mono = true)
        }
        iface.macAddress?.let { InfoRow("MAC", it, mono = true) }
        iface.mtu?.let { InfoRow("MTU", it.toString()) }
        iface.counters?.let { c ->
            InfoRow("RX / TX rate", "${Format.rate(iface.rxRateBps)} / ${Format.rate(iface.txRateBps)}")
            InfoRow("RX / TX total", "${Format.bytes(c.rxBytes)} / ${Format.bytes(c.txBytes)}")
            if (c.rxErrors + c.txErrors + c.rxDropped + c.txDropped > 0) {
                InfoRow("Errors / dropped", "${c.rxErrors + c.txErrors} / ${c.rxDropped + c.txDropped}")
            }
        }
    }
}

@Composable
fun UpstreamScreen(
    state: UiState,
    hasPhonePermission: Boolean,
    onGrantPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val up = state.snapshot?.upstream
    val traffic = state.snapshot?.deviceTraffic
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Shared connection", icon = Icons.Filled.Language) {
                if (up == null) {
                    Note("The device has no active internet connection, so the hotspot has nothing to share.")
                    return@SectionCard
                }
                Text(up.transport, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                InfoRow("Interface", up.interfaceName, mono = true)
                InfoRow("Internet validated", Format.yesNo(up.isValidated))
                InfoRow("Metered", Format.yesNo(up.isMetered))
                InfoRow("Est. downlink", Format.kbps(up.downstreamKbps))
                InfoRow("Est. uplink", Format.kbps(up.upstreamKbps))
                up.signalStrength?.let { InfoRow("Signal strength", "$it dBm") }
                up.gateway?.let { InfoRow("Gateway", it, mono = true) }
                up.addresses.forEach { InfoRow("Address", it, mono = true) }
                up.dnsServers.forEach { InfoRow("DNS server", it, mono = true) }
                up.privateDns?.let { InfoRow("Private DNS", it) }
                up.signalDetails.forEach { (k, v) -> InfoRow(k, v) }
            }
        }
        up?.cellular?.let { cell ->
            item {
                SectionCard(
                    title = "Cellular signal",
                    icon = Icons.Filled.SignalCellularAlt,
                    trailing = { SignalBars(cell.signalLevel) },
                ) {
                    InfoRow("Operator", cell.operatorName)
                    InfoRow("Network", cell.networkGeneration)
                    InfoRow("Signal", cell.signalDbm?.let { "$it dBm" })
                    InfoRow("Level", cell.signalLevel?.let { "$it / 4" })
                    InfoRow("Roaming", Format.yesNo(cell.roaming))
                    cell.signalDetails.forEach { (k, v) -> InfoRow(k, v) }
                    if (!hasPhonePermission) {
                        Note("Grant phone access to show the exact network type (for example 5G SA vs LTE).")
                        OutlinedButton(onClick = onGrantPhone) { Text("Grant phone access") }
                    }
                }
            }
        }
        traffic?.let { t ->
            item {
                SectionCard(title = "Device traffic since boot", icon = Icons.Filled.NetworkCheck) {
                    InfoRow("All networks ↓ / ↑", "${Format.bytes(t.totalRx)} / ${Format.bytes(t.totalTx)}")
                    InfoRow("Mobile ↓ / ↑", "${Format.bytes(t.mobileRx)} / ${Format.bytes(t.mobileTx)}")
                    Note("Includes hotspot clients' traffic, which passes through the upstream link.")
                }
            }
        }
    }
}
