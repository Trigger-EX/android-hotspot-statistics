package com.triggerex.hotspotstats.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.triggerex.hotspotstats.data.ApState
import com.triggerex.hotspotstats.data.HotspotConfig
import com.triggerex.hotspotstats.data.LocalHotspotController
import com.triggerex.hotspotstats.data.UsageState
import com.triggerex.hotspotstats.ui.Format
import com.triggerex.hotspotstats.ui.InfoRow
import com.triggerex.hotspotstats.ui.Note
import com.triggerex.hotspotstats.ui.SectionCard
import com.triggerex.hotspotstats.ui.StatusDot
import com.triggerex.hotspotstats.ui.UiState
import com.triggerex.hotspotstats.ui.theme.Quality

@Composable
fun OverviewScreen(
    state: UiState,
    onToggleRoot: (Boolean) -> Unit,
    onGrantUsage: () -> Unit,
    onStartLocalHotspot: () -> Unit,
    onStopLocalHotspot: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(state, onOpenHotspotSettings) }
        item { ConfigCard(snapshot?.config, state.rootEnabled) }
        item { TrafficCard(state) }
        item { UsageCard(snapshot?.usage, onGrantUsage) }
        item { RootCard(state, onToggleRoot) }
        item { LocalHotspotCard(state.localHotspot, onStartLocalHotspot, onStopLocalHotspot) }
    }
}

@Composable
private fun StatusCard(state: UiState, onOpenHotspotSettings: () -> Unit) {
    val snapshot = state.snapshot
    val apState = snapshot?.apState ?: ApState.UNKNOWN
    // The AP state can be unknown when reflection and the broadcast are both unavailable;
    // a live hotspot interface is then the best evidence.
    val effectivelyOn = apState == ApState.ENABLED || (apState == ApState.UNKNOWN && snapshot?.hotspotInterface != null)
    SectionCard(
        title = "Hotspot",
        icon = if (effectivelyOn) Icons.Filled.WifiTethering else Icons.Filled.WifiTetheringOff,
        trailing = {
            IconButton(onClick = onOpenHotspotSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Open hotspot settings")
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    effectivelyOn -> Quality.good
                    apState == ApState.ENABLING || apState == ApState.DISABLING -> Quality.fair
                    apState == ApState.FAILED -> Quality.poor
                    else -> MaterialTheme.colorScheme.outline
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (apState == ApState.UNKNOWN && effectivelyOn) "On (detected from interface)" else apState.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        snapshot?.apStateSource?.let { Note("State from: ${it.label}") }
        val iface = snapshot?.hotspotInterface
        if (iface != null) {
            InfoRow("Interface", iface.name + if (iface.tethered) " (tethered)" else "", mono = true)
            InfoRow("Hotspot IP", iface.ipv4?.let { "${it.address}/${it.prefixLength}" }, mono = true)
            iface.ipv4?.broadcast?.let { InfoRow("Broadcast", it, mono = true) }
            iface.addresses.filter { it.isIpv6 }.forEach { InfoRow("IPv6 (${it.scope})", "${it.address}/${it.prefixLength}", mono = true) }
            InfoRow("BSSID / MAC", iface.macAddress ?: "Hidden by Android", mono = iface.macAddress != null)
            InfoRow("MTU", iface.mtu?.toString())
            InfoRow("Connected clients", state.clients.size.toString())
        } else if (effectivelyOn || apState == ApState.ENABLING) {
            Note("The hotspot is on but its network interface has no IPv4 address yet.")
        } else {
            Note("Turn on the hotspot to see its interface, clients and traffic.")
        }
    }
}

@Composable
private fun ConfigCard(config: HotspotConfig?, rootEnabled: Boolean) {
    SectionCard(title = "Configuration", icon = Icons.Filled.Settings) {
        if (config == null) {
            Note(
                "Android does not let normal apps read the hotspot's name, password or band. " +
                    if (rootEnabled) {
                        "Root is on, but the saved hotspot configuration file was not found on this device."
                    } else {
                        "Enable root mode below to read the saved configuration, or start a local-only hotspot."
                    },
            )
            return@SectionCard
        }
        var reveal by rememberSaveable { mutableStateOf(false) }
        InfoRow("Network name (SSID)", config.ssid)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InfoRow(
                    "Password",
                    when {
                        config.passphrase == null -> null
                        reveal -> config.passphrase
                        else -> "•".repeat(config.passphrase.length.coerceAtMost(12))
                    },
                    mono = reveal,
                )
            }
            if (config.passphrase != null) {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (reveal) "Hide password" else "Show password",
                    )
                }
            }
        }
        InfoRow("Security", config.security)
        config.band?.let { InfoRow("Band", it) }
        config.channel?.let { InfoRow("Channel", it.toString()) }
        config.bssid?.let { InfoRow("BSSID", it, mono = true) }
        config.hidden?.let { InfoRow("Hidden network", Format.yesNo(it)) }
        config.maxClients?.let { InfoRow("Max clients", it.toString()) }
        config.autoShutdown?.let { InfoRow("Auto turn-off", Format.yesNo(it)) }
        Note("Source: ${config.source.label}")
    }
}

@Composable
private fun TrafficCard(state: UiState) {
    val iface = state.snapshot?.hotspotInterface ?: return
    SectionCard(title = "Live traffic", icon = Icons.Filled.SwapVert) {
        val c = iface.counters
        if (c == null) {
            Note("Interface counters are not readable on this device" + if (state.rootEnabled) "." else " without root.")
            return@SectionCard
        }
        // From the hotspot interface's point of view, TX is data sent to clients.
        InfoRow("To clients (down)", Format.rate(iface.txRateBps))
        InfoRow("From clients (up)", Format.rate(iface.rxRateBps))
        InfoRow("Sent to clients", Format.bytes(c.txBytes))
        InfoRow("Received from clients", Format.bytes(c.rxBytes))
        InfoRow("Packets TX / RX", "${c.txPackets} / ${c.rxPackets}")
        InfoRow("Errors TX / RX", "${c.txErrors} / ${c.rxErrors}")
        InfoRow("Dropped TX / RX", "${c.txDropped} / ${c.rxDropped}")
        Note("Counters since the interface came up.")
    }
}

@Composable
private fun UsageCard(usage: UsageState?, onGrantUsage: () -> Unit) {
    SectionCard(title = "Hotspot data usage", icon = Icons.Filled.DataUsage) {
        when (usage) {
            null -> Note("Loading…")
            UsageState.NeedsPermission -> {
                Note("Android records hotspot data per day. Grant \"Usage access\" to show it.")
                OutlinedButton(onClick = onGrantUsage) { Text("Grant usage access") }
            }
            is UsageState.Error -> Note("Could not read usage: ${usage.message}")
            is UsageState.Available -> {
                val u = usage.usage
                InfoRow("Today", "${Format.bytes(u.todayRx + u.todayTx)}  (↓ ${Format.bytes(u.todayRx)}, ↑ ${Format.bytes(u.todayTx)})")
                InfoRow("Last 30 days", "${Format.bytes(u.last30DaysRx + u.last30DaysTx)}  (↓ ${Format.bytes(u.last30DaysRx)}, ↑ ${Format.bytes(u.last30DaysTx)})")
                Note("All tethering (Wi-Fi, USB, Bluetooth), as counted by Android. Updated in batches by the system.")
            }
        }
    }
}

@Composable
private fun RootCard(state: UiState, onToggleRoot: (Boolean) -> Unit) {
    val root = state.snapshot?.root
    SectionCard(
        title = "Root mode",
        icon = Icons.Filled.AdminPanelSettings,
        trailing = {
            if (state.rootRequesting) {
                CircularProgressIndicator(Modifier.padding(4.dp).width(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = state.rootEnabled,
                    onCheckedChange = onToggleRoot,
                    enabled = root?.suAvailable == true || state.rootEnabled,
                )
            }
        },
    ) {
        Note(
            "With root the app reads per-client signal strength and link rates (iw), the ARP table, " +
                "DHCP hostnames and the saved hotspot name, password and band.",
        )
        if (root != null) {
            InfoRow("su binary", if (root.suAvailable) "Found" else "Not found")
            if (state.rootEnabled) InfoRow("iw tool", if (root.iwAvailable) "Available" else "Not installed – no per-client signal")
        }
    }
}

@Composable
private fun LocalHotspotCard(
    local: LocalHotspotController.State,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(title = "Local-only hotspot", icon = Icons.Filled.WifiTethering) {
        Note(
            "A hotspot started by this app, with no internet access. Android reveals its full " +
                "configuration, so it is useful for local file sharing or testing. Most devices cannot " +
                "run it together with the regular hotspot.",
        )
        when (local) {
            LocalHotspotController.State.Off -> Button(onClick = onStart) { Text("Start local-only hotspot") }
            LocalHotspotController.State.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Starting…")
            }
            is LocalHotspotController.State.On -> {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth()) {
                        InfoRow("SSID", local.config.ssid)
                        InfoRow("Password", local.config.passphrase, mono = true)
                        InfoRow("Security", local.config.security)
                    }
                }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            is LocalHotspotController.State.Failed -> {
                Text(local.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onStart) { Text("Try again") }
            }
        }
    }
}
