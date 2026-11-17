package com.triggerex.hotspotstats.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.triggerex.hotspotstats.data.ConnectedClient
import com.triggerex.hotspotstats.data.Parsers
import com.triggerex.hotspotstats.ui.Chip
import com.triggerex.hotspotstats.ui.Format
import com.triggerex.hotspotstats.ui.InfoRow
import com.triggerex.hotspotstats.ui.Note
import com.triggerex.hotspotstats.ui.SectionCard
import com.triggerex.hotspotstats.ui.SignalBars
import com.triggerex.hotspotstats.ui.UiState
import com.triggerex.hotspotstats.ui.latencyColor

@Composable
fun ClientsScreen(
    state: UiState,
    onRescan: () -> Unit,
    onCapture: (ConnectedClient) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hotspot = state.snapshot?.hotspotInterface
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "${state.clients.size} connected device" + if (state.clients.size == 1) "" else "s",
                icon = Icons.Filled.Devices,
                trailing = {
                    FilledTonalButton(onClick = onRescan, enabled = hotspot != null && state.scanProgress == null) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Scan")
                    }
                },
            ) {
                if (hotspot == null) {
                    Note("No active hotspot interface. Turn on the hotspot to discover clients.")
                } else {
                    InfoRow("Subnet", hotspot.ipv4?.let { "${it.address}/${it.prefixLength}" }, mono = true)
                    InfoRow("Last full scan", Format.ago(state.lastFullScanMs))
                    state.scanProgress?.let { p ->
                        Spacer(Modifier.padding(top = 4.dp))
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.probed / p.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Note("Probing ${p.probed} / ${p.total} addresses…")
                    }
                }
            }
        }
        if (state.notes.isNotEmpty() || !state.rootEnabled) {
            item { LimitationsCard(state) }
        }
        items(state.clients, key = { it.key }) { client ->
            ClientCard(client, canCapture = state.rootEnabled, onCapture = { onCapture(client) })
        }
    }
}

@Composable
private fun LimitationsCard(state: UiState) {
    SectionCard(title = "How clients are found", icon = Icons.Filled.Info) {
        Note(
            "Android does not give normal apps the hotspot's client list, so this app sweeps the " +
                "hotspot subnet with ping and TCP probes and reads whatever system tables it is allowed to.",
        )
        state.notes.forEach { Note("• $it") }
        if (!state.rootEnabled) {
            Note("• Per-client Wi-Fi signal strength and link rates need root mode with the iw tool.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientCard(client: ConnectedClient, canCapture: Boolean, onCapture: () -> Unit) {
    var expanded by rememberSaveable(client.key) { mutableStateOf(false) }
    val station = client.station
    val signal = station?.signalDbm ?: station?.signalAvgDbm
    val now = System.currentTimeMillis()
    val stale = now - client.lastSeenMs > 15_000

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        client.hostname ?: client.deviceHint ?: "Unknown device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        client.ip ?: "IP not yet known",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (signal != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        SignalBars(Format.wifiLevel(signal))
                        Text("$signal dBm", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                client.latencyMs?.let {
                    Chip("Ping ${Format.ms(it)}", latencyColor(it)?.copy(alpha = 0.22f) ?: MaterialTheme.colorScheme.secondaryContainer)
                }
                if (client.hostname != null && client.deviceHint != null) Chip(client.deviceHint)
                if (client.isRandomizedMac) Chip("Private MAC")
                if (stale) Chip("Last seen ${Format.ago(client.lastSeenMs, now)}", MaterialTheme.colorScheme.errorContainer)
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SelectionContainer {
                    Column {
                        InfoRow("IP address", client.ip, mono = true)
                        InfoRow("MAC address", client.mac ?: "Unavailable without root", mono = client.mac != null)
                        if (client.mac != null) {
                            InfoRow(
                                "MAC type",
                                if (client.isRandomizedMac) "Randomised (private)" else "Hardware (globally unique)",
                            )
                        }
                        InfoRow("Hostname", client.hostname?.let { "$it (${client.hostnameSource})" })
                        InfoRow("Device type", client.deviceHint)
                        InfoRow("Latency", Format.ms(client.latencyMs), valueColor = latencyColor(client.latencyMs))
                        if (client.openPorts.isNotEmpty()) {
                            InfoRow(
                                "Open ports",
                                client.openPorts.joinToString(", ") { p -> Parsers.HINT_PORTS[p]?.let { "$p ($it)" } ?: "$p" },
                            )
                        }
                        client.neighbourState?.let { InfoRow("ARP state", it) }

                        if (station != null) {
                            Spacer(Modifier.padding(top = 6.dp))
                            Text("Wi-Fi link", style = MaterialTheme.typography.titleSmall)
                            signal?.let { InfoRow("Signal", "$it dBm (${Format.wifiQuality(it)})") }
                            station.signalAvgDbm?.let { InfoRow("Signal average", "$it dBm") }
                            InfoRow("TX rate (to device)", Format.mbps(station.txBitrateMbps))
                            InfoRow("RX rate (from device)", Format.mbps(station.rxBitrateMbps))
                            InfoRow("Sent to device", Format.bytes(station.txBytes))
                            InfoRow("Received from device", Format.bytes(station.rxBytes))
                            InfoRow("Packets TX / RX", "${station.txPackets ?: "—"} / ${station.rxPackets ?: "—"}")
                            InfoRow("TX retries / failed", "${station.txRetries ?: "—"} / ${station.txFailed ?: "—"}")
                            InfoRow("Connected for", Format.duration(station.connectedSeconds))
                            InfoRow("Inactive for", station.inactiveMs?.let { Format.ms(it.toDouble()) })
                            station.authorized?.let { InfoRow("Authorised", Format.yesNo(it)) }
                        }

                        Spacer(Modifier.padding(top = 6.dp))
                        InfoRow("First seen", Format.time(client.firstSeenMs))
                        InfoRow("Last seen", Format.ago(client.lastSeenMs, now))
                        InfoRow("Found by", client.sources.joinToString(", ") { it.label })
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                OutlinedButton(onClick = onCapture, enabled = canCapture) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (canCapture) "Capture this device's packets" else "Packet capture needs root")
                }
            }
        }
    }
}
