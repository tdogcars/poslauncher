package com.blurredlimes.pivotlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class NetType { WIFI, ETHERNET, CELLULAR, OFFLINE }

data class NetStatus(val type: NetType, val name: String?)

// The only network endpoint this app ever contacts, and only when the user
// taps "Run speed test". Download-only; nothing is uploaded.
private const val SPEED_HOST = "https://speed.cloudflare.com"
private const val PING_URL = "$SPEED_HOST/__down?bytes=1"

// Cloudflare 403s requests above ~50 MB, so the test chains 50 MB requests
// (keep-alive reuses the connection) until the time budget is spent.
private const val DOWNLOAD_URL = "$SPEED_HOST/__down?bytes=50000000"
private const val TEST_DURATION_MS = 8_000L

fun readNetworkStatus(context: Context): NetStatus {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        ?: return NetStatus(NetType.OFFLINE, null)
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            NetStatus(NetType.WIFI, readSsid(context))
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            NetStatus(NetType.ETHERNET, null)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            NetStatus(NetType.CELLULAR, null)
        else -> NetStatus(NetType.OFFLINE, null)
    }
}

/**
 * The SSID is location-gated by Android (8.1+): without ACCESS_FINE_LOCATION
 * and location services enabled the system returns "<unknown ssid>", in which
 * case the widget falls back to the generic "Wi-Fi" label.
 */
private fun readSsid(context: Context): String? {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val wm = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    @Suppress("DEPRECATION")
    val raw = wm.connectionInfo?.ssid ?: return null
    val ssid = raw.removeSurrounding("\"")
    return ssid.takeUnless { it.isBlank() || it.contains("unknown ssid") }
}

/** Current network status, refreshed on connectivity changes and [keys]. */
@Composable
fun rememberNetworkStatus(vararg keys: Any?): NetStatus {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { tick++ }
            override fun onLost(network: Network) { tick++ }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) { tick++ }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    return remember(tick, *keys) { readNetworkStatus(context) }
}

@Composable
private fun NetStatus.typeLabel(): String = stringResource(
    when (type) {
        NetType.WIFI -> R.string.net_wifi
        NetType.ETHERNET -> R.string.net_ethernet
        NetType.CELLULAR -> R.string.net_cellular
        NetType.OFFLINE -> R.string.net_offline
    }
)

/** Connection dot plus network name (SSID when Android lets us read it). */
@Composable
fun NetworkStatusWidget(status: NetStatus, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (status.type == NetType.OFFLINE) Color(0xFFE53935)
                    else Color(0xFF4CAF50)
                ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = status.name ?: status.typeLabel(),
            color = Color.White,
            fontSize = 15.sp,
        )
    }
}

/**
 * Speed test that lives directly on the home screen: a button, then a live
 * Mbps readout while running and "Mbps · latency" when done. Failures show
 * the underlying cause (timeout, DNS, TLS) instead of a generic message.
 */
@Composable
fun SpeedTestInline(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var finalMbps by remember { mutableStateOf<Double?>(null) }
    var liveMbps by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        error = null
        latencyMs = null
        finalMbps = null
        liveMbps = null
        running = true
        job = scope.launch {
            try {
                latencyMs = measureLatencyMs()
                finalMbps = measureDownloadMbps { liveMbps = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SpeedTest", "speed test failed", e)
                error = e.javaClass.simpleName +
                    (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            } finally {
                running = false
            }
        }
    }

    Column(modifier = modifier) {
        OutlinedButton(onClick = { if (running) job?.cancel() else start() }) {
            Text(
                text = stringResource(
                    if (running) R.string.speedtest_cancel else R.string.speedtest_run
                ),
                fontSize = 13.sp,
            )
        }
        when {
            running -> Text(
                text = liveMbps?.let { "%.1f Mbps…".format(it) }
                    ?: stringResource(R.string.speedtest_testing),
                color = Color(0xFFBBBBBB),
                fontSize = 14.sp,
            )

            error != null -> Text(
                text = stringResource(R.string.speedtest_failed_detail, error.orEmpty()),
                color = Color(0xFFE57373),
                fontSize = 13.sp,
                modifier = Modifier.widthIn(max = 320.dp),
            )

            finalMbps != null -> Text(
                text = stringResource(
                    R.string.speedtest_result,
                    "%.1f".format(finalMbps),
                    latencyMs ?: 0L,
                ),
                color = Color.White,
                fontSize = 14.sp,
            )
        }
    }
}

/** Best of three small requests; keep-alive reuse makes later rounds ~pure RTT. */
private suspend fun measureLatencyMs(): Long = withContext(Dispatchers.IO) {
    var best = Long.MAX_VALUE
    repeat(3) {
        ensureActive()
        val conn = (URL(PING_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        val start = SystemClock.elapsedRealtime()
        conn.inputStream.use { it.readBytes() }
        best = minOf(best, SystemClock.elapsedRealtime() - start)
    }
    best
}

private suspend fun measureDownloadMbps(onProgress: (Double) -> Unit): Double =
    withContext(Dispatchers.IO) {
        val buffer = ByteArray(64 * 1024)
        var totalBytes = 0L
        val start = SystemClock.elapsedRealtime()
        var lastUpdate = start

        fun elapsed() = SystemClock.elapsedRealtime() - start

        while (elapsed() < TEST_DURATION_MS) {
            ensureActive()
            val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            conn.inputStream.use { input ->
                while (elapsed() < TEST_DURATION_MS) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 250) {
                        lastUpdate = now
                        onProgress(toMbps(totalBytes, now - start))
                    }
                }
            }
            // Only sever the connection when the window expired mid-response;
            // fully-read responses go back to the keep-alive pool for reuse.
            if (elapsed() >= TEST_DURATION_MS) conn.disconnect()
        }
        toMbps(totalBytes, maxOf(1, elapsed()))
    }

private fun toMbps(bytes: Long, elapsedMs: Long): Double =
    bytes * 8.0 / (elapsedMs * 1000.0)
