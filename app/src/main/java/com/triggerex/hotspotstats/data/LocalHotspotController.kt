package com.triggerex.hotspotstats.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starts the app's own local-only hotspot. It is the one hotspot whose full configuration
 * (SSID, passphrase, security) Android hands to a normal app. It has no internet access and
 * cannot run alongside the regular hotspot on most single-radio devices.
 */
class LocalHotspotController(context: Context) {

    sealed interface State {
        data object Off : State
        data object Starting : State
        data class On(val config: HotspotConfig) : State
        data class Failed(val reason: String) : State
    }

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Caller must hold location (API < 33) or NEARBY_WIFI_DEVICES (API 33+). */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value is State.On || _state.value is State.Starting) return
        _state.value = State.Starting
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    _state.value = State.On(configOf(res))
                }

                override fun onStopped() {
                    reservation = null
                    _state.value = State.Off
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    _state.value = State.Failed(failureText(reason))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            _state.value = State.Failed("Permission denied: ${e.message}")
        } catch (e: IllegalStateException) {
            _state.value = State.Failed(e.message ?: "Already running")
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        _state.value = State.Off
    }

    @Suppress("DEPRECATION")
    private fun configOf(res: WifiManager.LocalOnlyHotspotReservation): HotspotConfig {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c: SoftApConfiguration = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                c.wifiSsid?.toString()?.let(Parsers::decodeWifiSsid)
            } else {
                c.ssid
            }
            return HotspotConfig(
                ssid = ssid,
                bssid = c.bssid?.toString(),
                passphrase = c.passphrase,
                security = Parsers.securityTypeName(c.securityType),
                band = null,
                hidden = c.isHiddenSsid,
                source = Source.LOCAL_ONLY,
            )
        }
        val c = res.wifiConfiguration
        return HotspotConfig(
            ssid = c?.SSID?.let(Parsers::decodeWifiSsid),
            passphrase = c?.preSharedKey?.trim('"'),
            security = if (c?.preSharedKey.isNullOrEmpty()) "Open" else "WPA2-Personal",
            band = null,
            hidden = c?.hiddenSSID,
            source = Source.LOCAL_ONLY,
        )
    }

    private fun failureText(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No usable channel"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Tethering is disallowed on this device"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Incompatible with the current mode (turn off the regular hotspot first)"
        else -> "Failed (code $reason)"
    }
}
