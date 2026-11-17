package com.triggerex.hotspotstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.triggerex.hotspotstats.capture.CaptureFile
import com.triggerex.hotspotstats.capture.CaptureState
import com.triggerex.hotspotstats.ui.CaptureOptions
import com.triggerex.hotspotstats.ui.Format
import com.triggerex.hotspotstats.ui.InfoRow
import com.triggerex.hotspotstats.ui.Note
import com.triggerex.hotspotstats.ui.SectionCard
import com.triggerex.hotspotstats.ui.UiState
import com.triggerex.hotspotstats.ui.theme.Quality

private val DURATIONS = listOf(60 to "1 min", 300 to "5 min", 900 to "15 min", 3600 to "1 h", 8 * 3600 to "8 h")
private val SIZES = listOf(10L to "10 MB", 50L to "50 MB", 200L to "200 MB", 1024L to "1 GB")

@Composable
fun CaptureScreen(
    state: UiState,
    capture: CaptureState?,
    files: List<CaptureFile>,
    onToggleHost: (String) -> Unit,
    onClearSelection: () -> Unit,
    onStart: (CaptureOptions) -> Unit,
    onStop: () -> Unit,
    onShare: (CaptureFile) -> Unit,
    onDelete: (CaptureFile) -> Unit,
    onOpenOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var durationIndex by rememberSaveable { mutableIntStateOf(1) }
    var sizeIndex by rememberSaveable { mutableIntStateOf(1) }
    var headersOnly by rememberSaveable { mutableStateOf(false) }
    val running = capture?.running == true
    val hotspot = state.snapshot?.hotspotInterface

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.rootEnabled) {
            item {
                SectionCard(title = "Root required", icon = Icons.Filled.Lock) {
                    Note(
                        "Android forwards hotspot traffic inside the kernel, so no app can see other " +
                            "devices' packets without root — not even VPN-based capture apps, because " +
                            "tethered traffic bypasses VPNs.",
                    )
                    OutlinedButton(onClick = onOpenOverview) { Text("Turn on root mode") }
                }
            }
        }

        if (capture != null) {
            item { LiveCaptureCard(capture, onStop) }
        }

        item {
            SectionCard(
                title = "Devices to capture",
                icon = Icons.Filled.Devices,
                trailing = {
                    if (state.captureSelection.isNotEmpty() && !running) {
                        TextButton(onClick = onClearSelection) { Text("Clear") }
                    }
                },
            ) {
                when {
                    hotspot == null -> Note("Turn on the hotspot to choose devices.")
                    state.clients.isEmpty() -> Note("No devices found yet. With nothing selected, all hotspot traffic is captured.")
                    else -> {
                        Note(
                            if (state.captureSelection.isEmpty()) {
                                "Nothing selected: all traffic on ${hotspot.name} will be captured."
                            } else {
                                "Only packets to or from the selected devices are kept."
                            },
                        )
                        state.clients.forEach { client ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !running) { onToggleHost(client.key) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = client.key in state.captureSelection,
                                    onCheckedChange = { onToggleHost(client.key) },
                                    enabled = !running,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(client.hostname ?: client.deviceHint ?: "Unknown device", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOfNotNull(client.ip, client.mac).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (state.clients.any { it.key in state.captureSelection && it.mac == null }) {
                            Note("Devices without a known MAC are matched by IPv4 address only, so their IPv6 traffic is not included.")
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Options", icon = Icons.Filled.Tune) {
                Text("Stop after", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DURATIONS.forEachIndexed { i, (_, label) ->
                        SegmentedButton(
                            selected = durationIndex == i,
                            onClick = { durationIndex = i },
                            shape = SegmentedButtonDefaults.itemShape(i, DURATIONS.size),
                            enabled = !running,
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text("Maximum file size", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SIZES.forEachIndexed { i, (_, label) ->
                        SegmentedButton(
                            selected = sizeIndex == i,
                            onClick = { sizeIndex = i },
                            shape = SegmentedButtonDefaults.itemShape(i, SIZES.size),
                            enabled = !running,
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Headers only", style = MaterialTheme.typography.bodyLarge)
                        Note("Keeps the first 128 bytes of each packet: smaller files, no payloads.")
                    }
                    Switch(checked = headersOnly, onCheckedChange = { headersOnly = it }, enabled = !running)
                }
                Spacer(Modifier.padding(top = 4.dp))
                Button(
                    onClick = {
                        onStart(
                            CaptureOptions(
                                durationSec = DURATIONS[durationIndex].first,
                                maxBytes = SIZES[sizeIndex].first * 1024 * 1024,
                                headersOnly = headersOnly,
                            ),
                        )
                    },
                    enabled = !running && state.rootEnabled && hotspot != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state.captureSelection.size) {
                            0 -> "Capture all hotspot traffic"
                            1 -> "Capture 1 device"
                            else -> "Capture ${state.captureSelection.size} devices"
                        },
                    )
                }
                Note("Only capture devices you own or are allowed to monitor.")
            }
        }

        if (capture != null && capture.recent.isNotEmpty()) {
            item { Text("Recent packets (newest first)", style = MaterialTheme.typography.titleSmall) }
            items(capture.recent.take(150), key = null) { p ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        Format.clock(p.epochMs).substringAfter(':'),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(
                        p.summary + if (p.length > 0) "  (${p.length} B)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Saved captures", icon = Icons.Filled.Folder) {
                if (files.isEmpty()) {
                    Note("No captures yet. Files are pcap format: open them in Wireshark or any pcap tool.")
                }
                files.forEach { f ->
                    val active = running && capture?.file == f.file
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Note(
                                if (active) "Recording…" else "${Format.bytes(f.sizeBytes)} · ${Format.time(f.modifiedMs)}",
                            )
                        }
                        IconButton(onClick = { onShare(f) }, enabled = !active) {
                            Icon(Icons.Filled.Share, contentDescription = "Share ${f.file.name}")
                        }
                        IconButton(onClick = { onDelete(f) }, enabled = !active) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${f.file.name}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCaptureCard(capture: CaptureState, onStop: () -> Unit) {
    val title = when {
        capture.stopping -> "Stopping…"
        capture.running && capture.capturing -> "Capturing on ${capture.request.iface}"
        capture.running -> "Starting…"
        capture.error != null -> "Capture failed"
        else -> "Capture finished"
    }
    SectionCard(title = title, icon = Icons.Filled.FiberManualRecord) {
        if (capture.running && !capture.capturing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Note("Waiting for root access…")
        }
        capture.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        InfoRow(
            "Devices",
            if (capture.request.hosts.isEmpty()) "All traffic" else capture.request.hosts.joinToString(", ") { it.label },
        )
        InfoRow("Duration", Format.duration(capture.elapsedMs / 1000))
        InfoRow("Packets captured", capture.packets.toString())
        InfoRow("Traffic captured", Format.bytes(capture.bytes))
        InfoRow("File size", Format.bytes(capture.fileBytes))
        InfoRow("Packets examined", capture.seen.toString())
        InfoRow(
            "Dropped by kernel",
            capture.kernelDrops.toString(),
            valueColor = if (capture.kernelDrops > 0) Quality.fair else null,
        )
        capture.endReason?.let { InfoRow("Ended", it) }
        if (capture.running) {
            Button(
                onClick = onStop,
                enabled = !capture.stopping,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop capture", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
