package com.triggerex.hotspotstats.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Reads whether the hotspot is on and which interfaces are tethered.
 *
 * Neither has a public API. The hidden `WifiManager.getWifiApState()` still answers through
 * reflection on most builds, and the two sticky system broadcasts below are delivered to any app,
 * so they back each other up.
 */
class HotspotStateReader(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    fun readApState(): Pair<ApState, Source?> {
        try {
            val method = WifiManager::class.java.getMethod("getWifiApState")
            val value = method.invoke(wifiManager) as Int
            val state = ApState.fromWifiManagerValue(value)
            if (state != ApState.UNKNOWN) return state to Source.HIDDEN_API
        } catch (_: Throwable) {
            // Blocked by hidden-API enforcement on this build; fall through to the broadcast.
        }
        val sticky = stickyIntent(ACTION_WIFI_AP_STATE_CHANGED)
        val value = sticky?.getIntExtra(EXTRA_WIFI_AP_STATE, -1) ?: -1
        if (value >= 0) return ApState.fromWifiManagerValue(value) to Source.BROADCAST
        return ApState.UNKNOWN to null
    }

    /**
     * Interfaces the tethering service reports as active (`tetherArray`) plus any
     * local-only hotspot interfaces. Null when the broadcast is unavailable.
     */
    fun readTetheredInterfaces(): Set<String>? {
        val sticky = stickyIntent(ACTION_TETHER_STATE_CHANGED) ?: return null
        val active = sticky.getStringArrayListExtra(EXTRA_ACTIVE_TETHER).orEmpty()
        val localOnly = sticky.getStringArrayListExtra(EXTRA_ACTIVE_LOCAL_ONLY).orEmpty()
        return (active + localOnly).toSet()
    }

    /** Emits whenever hotspot or tethering state changes. */
    fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
            addAction(ACTION_TETHER_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    private fun stickyIntent(action: String): Intent? = try {
        ContextCompat.registerReceiver(appContext, null, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        const val EXTRA_ACTIVE_TETHER = "tetherArray"
        const val EXTRA_ACTIVE_LOCAL_ONLY = "android.net.extra.ACTIVE_LOCAL_ONLY"
    }
}
