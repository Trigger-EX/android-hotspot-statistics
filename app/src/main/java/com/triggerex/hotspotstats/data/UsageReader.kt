package com.triggerex.hotspotstats.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Hotspot data usage. Android accounts all tethered traffic to the special UID_TETHERING
 * bucket in NetworkStatsManager, which any app may read once the user grants "Usage access".
 */
class UsageReader(context: Context) {

    private val appContext = context.applicationContext
    private val statsManager = appContext.getSystemService(NetworkStatsManager::class.java)

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun read(): UsageState = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext UsageState.NeedsPermission
        try {
            val now = System.currentTimeMillis()
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val monthAgo = now - 30L * 24 * 60 * 60 * 1000
            val (todayRx, todayTx) = tetheringBytes(midnight, now)
            val (monthRx, monthTx) = tetheringBytes(monthAgo, now)
            UsageState.Available(TetheringUsage(todayRx, todayTx, monthRx, monthTx))
        } catch (e: Exception) {
            UsageState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun tetheringBytes(start: Long, end: Long): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        // Hotspot traffic is counted on the upstream network type: usually mobile,
        // sometimes Wi-Fi (Wi-Fi sharing) or Ethernet.
        for (type in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET)) {
            val stats = try {
                statsManager.querySummary(type, null, start, end)
            } catch (_: Exception) {
                continue
            }
            stats.use {
                val bucket = NetworkStats.Bucket()
                while (it.hasNextBucket()) {
                    it.getNextBucket(bucket)
                    if (bucket.uid == NetworkStats.Bucket.UID_TETHERING) {
                        rx += bucket.rxBytes
                        tx += bucket.txBytes
                    }
                }
            }
        }
        return rx to tx
    }

    fun deviceTraffic(): DeviceTraffic? {
        val totalRx = TrafficStats.getTotalRxBytes()
        if (totalRx == TrafficStats.UNSUPPORTED.toLong()) return null
        return DeviceTraffic(
            totalRx = totalRx,
            totalTx = TrafficStats.getTotalTxBytes(),
            mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0),
            mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0),
        )
    }
}

private inline fun <R> NetworkStats.use(block: (NetworkStats) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
