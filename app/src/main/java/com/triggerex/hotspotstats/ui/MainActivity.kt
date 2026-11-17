package com.triggerex.hotspotstats.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.triggerex.hotspotstats.ui.screens.CaptureScreen
import com.triggerex.hotspotstats.ui.screens.ClientsScreen
import com.triggerex.hotspotstats.ui.screens.InterfacesScreen
import com.triggerex.hotspotstats.ui.screens.OverviewScreen
import com.triggerex.hotspotstats.ui.screens.UpstreamScreen
import com.triggerex.hotspotstats.ui.theme.HotspotTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HotspotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HotspotTheme {
                App(viewModel)
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Filled.Dashboard),
    CLIENTS("Clients", Icons.Filled.Devices),
    CAPTURE("Capture", Icons.Filled.FiberManualRecord),
    INTERFACES("Interfaces", Icons.Filled.Hub),
    UPSTREAM("Upstream", Icons.Filled.Language),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(viewModel: HotspotViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val capture by viewModel.capture.collectAsStateWithLifecycle()
    val captureFiles by viewModel.captureFiles.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var hasPhonePermission by remember { mutableStateOf(context.has(Manifest.permission.READ_PHONE_STATE)) }

    LifecycleStartEffect(Unit) {
        viewModel.setActive(true)
        hasPhonePermission = context.has(Manifest.permission.READ_PHONE_STATE)
        onStopOrDispose { viewModel.setActive(false) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val localHotspotPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) viewModel.startLocalHotspot()
    }
    // The capture notification is optional; capture runs either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPhonePermission = granted
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hotspot Statistics") },
                actions = {
                    IconButton(onClick = {
                        viewModel.refresh()
                        viewModel.rescan()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh and rescan")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                t.icon,
                                contentDescription = null,
                                tint = if (t == Tab.CAPTURE && capture?.running == true) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    androidx.compose.material3.LocalContentColor.current
                                },
                            )
                        },
                        label = {
                            Text(
                                if (t == Tab.CLIENTS) "${t.label} (${state.clients.size})" else t.label,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (state.snapshot == null) {
            Column(modifier.padding(16.dp)) {
                Text("Reading hotspot state…", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }
        when (Tab.entries[tab]) {
            Tab.OVERVIEW -> OverviewScreen(
                state = state,
                onToggleRoot = viewModel::setRootEnabled,
                onGrantUsage = { context.openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                onStartLocalHotspot = {
                    val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                    } else {
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    if (needed.all(context::has)) viewModel.startLocalHotspot() else localHotspotPermission.launch(needed)
                },
                onStopLocalHotspot = viewModel::stopLocalHotspot,
                onOpenHotspotSettings = { context.openHotspotSettings() },
                modifier = modifier,
            )
            Tab.CLIENTS -> ClientsScreen(
                state,
                onRescan = viewModel::rescan,
                onCapture = { client ->
                    viewModel.selectOnlyForCapture(client.key)
                    tab = Tab.CAPTURE.ordinal
                },
                modifier = modifier,
            )
            Tab.CAPTURE -> CaptureScreen(
                state = state,
                capture = capture,
                files = captureFiles,
                onToggleHost = viewModel::toggleCaptureHost,
                onClearSelection = viewModel::clearCaptureSelection,
                onStart = { options ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !context.has(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.startCapture(options)?.let { error -> scope.launch { snackbar.showSnackbar(error) } }
                },
                onStop = viewModel::stopCapture,
                onShare = { file -> context.startActivity(viewModel.shareCapture(file)) },
                onDelete = viewModel::deleteCapture,
                onOpenOverview = { tab = Tab.OVERVIEW.ordinal },
                modifier = modifier,
            )
            Tab.INTERFACES -> InterfacesScreen(state, modifier = modifier)
            Tab.UPSTREAM -> UpstreamScreen(
                state = state,
                hasPhonePermission = hasPhonePermission,
                onGrantPhone = { phonePermission.launch(Manifest.permission.READ_PHONE_STATE) },
                modifier = modifier,
            )
        }
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** There is no public action for the hotspot screen; try the AOSP one, then fall back. */
private fun Context.openHotspotSettings() {
    val candidates = listOf(
        Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
    for (intent in candidates) {
        try {
            startActivity(intent)
            return
        } catch (_: Exception) {
            // Try the next one.
        }
    }
}
