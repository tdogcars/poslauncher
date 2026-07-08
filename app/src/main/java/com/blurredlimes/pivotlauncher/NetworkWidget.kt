package com.blurredlimes.pivotlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
private const val DOWNLOAD_URL = "$SPEED_HOST/__down?bytes=200000000"
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

/** Top-left status pill: connection dot plus network name (SSID when readable). */
@Composable
fun NetworkStatusWidget(
    status: NetStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
 * In-place overlay: shows the connection and runs a download speed test
 * without leaving the home screen. Dismiss by tapping outside or Close;
 * leaving the panel cancels a running test.
 */
@Composable
fun NetworkPanel(status: NetStatus, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var finalMbps by remember { mutableStateOf<Double?>(null) }
    var liveMbps by remember { mutableStateOf<Double?>(null) }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        failed = false
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
                failed = true
            } finally {
                running = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141414))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // swallow taps so they don't dismiss
                )
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = status.name ?: status.typeLabel(),
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            if (status.name != null) {
                Text(
                    text = status.typeLabel(),
                    color = Color(0xFF999999),
                    fontSize = 14.sp,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            val shownMbps = finalMbps ?: liveMbps
            Text(
                text = shownMbps?.let { "%.1f".format(it) } ?: "—",
                color = if (finalMbps != null) Color.White else Color(0xFFBBBBBB),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.speedtest_download_label),
                color = Color(0xFF999999),
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = latencyMs?.let { stringResource(R.string.speedtest_latency, it) } ?: " ",
                color = Color(0xFF999999),
                fontSize = 14.sp,
            )
            if (failed) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.speedtest_failed),
                    color = Color(0xFFE57373),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    OutlinedButton(onClick = { job?.cancel() }) {
                        Text(text = stringResource(R.string.speedtest_cancel))
                    }
                } else {
                    Button(onClick = { start() }) {
                        Text(text = stringResource(R.string.speedtest_run))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.speedtest_close))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.speedtest_hint),
                color = Color(0xFF666666),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
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
            connectTimeout = 5_000
            readTimeout = 5_000
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
        val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var totalBytes = 0L
                val start = SystemClock.elapsedRealtime()
                var lastUpdate = start
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - start >= TEST_DURATION_MS) break
                    if (now - lastUpdate >= 250) {
                        lastUpdate = now
                        onProgress(toMbps(totalBytes, now - start))
                    }
                }
                toMbps(totalBytes, maxOf(1, SystemClock.elapsedRealtime() - start))
            }
        } finally {
            conn.disconnect()
        }
    }

private fun toMbps(bytes: Long, elapsedMs: Long): Double =
    bytes * 8.0 / (elapsedMs * 1000.0)
