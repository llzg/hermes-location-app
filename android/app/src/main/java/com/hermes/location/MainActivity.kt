package com.hermes.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

// ── Colors ──────────────────────────────────────────────────
private val Green = Color(0xFF42D392)
private val GreenDim = Color(0xFF2A8A5E)
private val Red = Color(0xFFDC2626)
private val WhitePure = Color(0xFFF1F5F9)
private val WhiteSoft = Color(0xFFCBD5E1)
private val WhiteMuted = Color(0xFF94A3B8)
private val BgDeep = Color(0xFF080D18)
private val BgCard = Color(0xE6111827)
private val BgSurface = Color(0xFF111827)
private val BorderColor = Color(0xFF1E293B)

// ── Constants ───────────────────────────────────────────────
private const val Endpoint = "https://location.lzghs.top:16666/location"
private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
private const val AUTO_REFRESH_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(LocationUpdateService.PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            HermesLocationTheme {
                HermesLocationApp(
                    initialLat = prefs.getFloat(LocationUpdateService.PREF_LAT, 0f)
                        .takeIf { it != 0f }?.toDouble(),
                    initialLng = prefs.getFloat(LocationUpdateService.PREF_LNG, 0f)
                        .takeIf { it != 0f }?.toDouble(),
                    initialTime = prefs.getString(LocationUpdateService.PREF_TIME, null),
                    initialResponse = prefs.getString(LocationUpdateService.PREF_RESPONSE, null)
                )
            }
        }
    }
}

@Composable
private fun HermesLocationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            onPrimary = Color(0xFF06130D),
            secondary = Color(0xFF7DD3FC),
            background = BgDeep,
            surface = BgSurface,
            onSurface = WhiteSoft,
            surfaceVariant = Color(0xFF1F2937),
            outline = BorderColor
        ),
        content = content
    )
}

@Composable
private fun HermesLocationApp(
    initialLat: Double? = null,
    initialLng: Double? = null,
    initialTime: String? = null,
    initialResponse: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val httpClient = remember { OkHttpClient() }

    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PermissionChecker.PERMISSION_GRANTED
        )
    }
    var lat by remember { mutableStateOf(initialLat) }
    var lng by remember { mutableStateOf(initialLng) }
    var city by remember { mutableStateOf(initialResponse ?: "Waiting for server response") }
    var status by remember { mutableStateOf(if (hasFineLocation) "Ready" else "Location permission required") }
    var loading by remember { mutableStateOf(false) }
    var lastUpdateTime by remember { mutableStateOf(initialTime ?: "--:--:--") }
    var serviceRunning by remember { mutableStateOf(isServiceRunning(context)) }
    var countdown by remember { mutableIntStateOf(60) }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFineLocation = granted
        status = if (granted) "Permission granted" else "Fine location permission denied"
    }
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort */ }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort */ }

    // ── Poll service state ──
    LaunchedEffect(serviceRunning) {
        while (true) {
            if (serviceRunning) {
                LocationUpdateService.run {
                    if (lat != this.lat || lng != this.lng) {
                        lat = this.lat
                        lng = this.lng
                    }
                    if (this.lastUpdateTime.isNotEmpty()) lastUpdateTime = this.lastUpdateTime
                    if (this.serverResponse.isNotEmpty()) city = this.serverResponse
                }
            }
            delay(3_000)
        }
    }

    // ── Auto-refresh timer (only if service not running) ──
    LaunchedEffect(hasFineLocation, serviceRunning) {
        if (hasFineLocation) {
            while (true) {
                if (!serviceRunning) {
                    val result = getAndSendLocation(locationClient, httpClient, fineLauncher, hasFineLocation)
                    if (result != null) {
                        lat = result.first
                        lng = result.second
                        city = result.third
                        lastUpdateTime = result.fourth
                    }
                }
                for (i in 60 downTo 1) {
                    countdown = i
                    delay(1_000)
                    if (serviceRunning) break
                }
                if (serviceRunning) {
                    countdown = 0
                    delay(AUTO_REFRESH_MS)
                }
            }
        }
    }

    // ── Poll service alive ──
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = isServiceRunning(context)
            delay(5_000)
        }
    }

    // ── Update notification when city changes ──
    DisposableEffect(city) {
        if (serviceRunning) {
            context.startService(Intent(context, LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_UPDATE_NOTIFICATION
            })
        }
        onDispose { }
    }

    // ═══════════════════════════════ UI ═══════════════════════════

    Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(
                    listOf(Color(0xFF08111F), Color(0xFF0A0F1A), BgDeep)
                ))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Header ──
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Hermes Location",
                        color = WhitePure,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusDot(active = serviceRunning || loading)
                        Text(
                            text = when {
                                loading -> "Fetching GPS..."
                                serviceRunning -> "Background tracking active"
                                else -> status
                            },
                            color = WhiteMuted,
                            style = MaterialTheme.typography.bodySmall,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                // ── Location Card ──
                Card(
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CoordRow("Latitude", lat?.let { String.format("%.6f", it) } ?: "--")
                        CoordRow("Longitude", lng?.let { String.format("%.6f", it) } ?: "--")

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderColor))

                        Text(
                            text = "Server response",
                            color = WhiteMuted,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = city,
                            color = Green,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⏱", fontSize = 12.sp)
                            Text(
                                text = "Last updated: $lastUpdateTime",
                                color = WhiteMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!serviceRunning && countdown < 60) {
                                Text(
                                    text = "· next in ${countdown}s",
                                    color = WhiteMuted.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // ── Buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Send GPS once
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true; status = "Reading GPS location"
                                val result = getAndSendLocation(locationClient, httpClient, fineLauncher, hasFineLocation)
                                if (result != null) {
                                    lat = result.first; lng = result.second
                                    city = result.third; lastUpdateTime = result.fourth
                                    status = "Location sent"
                                } else {
                                    status = "Unable to read device location"
                                }
                                loading = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !loading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color(0xFF06130D))
                    ) {
                        Text(if (loading) "Sending..." else "Send GPS",
                            fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    }

                    // BG Track toggle
                    Button(
                        onClick = {
                            if (serviceRunning) {
                                context.stopService(Intent(context, LocationUpdateService::class.java))
                            } else {
                                if (!hasFineLocation) { fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return@Button }
                                if (Build.VERSION.SDK_INT >= 30)
                                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33)
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                ContextCompat.startForegroundService(context, Intent(context, LocationUpdateService::class.java))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serviceRunning) Red else BgSurface,
                            contentColor = if (serviceRunning) WhitePure else Green
                        )
                    ) {
                        Text(if (serviceRunning) "Stop BG" else "BG Track",
                            fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    }
                }

                // Reset to Dongguan
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true; status = "Resetting city"
                            city = runCatching {
                                httpClient.postJson("""{"city":"东莞","reset":true}""")
                            }.getOrElse { "Request failed: ${it.message ?: "unknown error"}" }
                            status = "Reset sent"; loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WhiteMuted),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(listOf(BorderColor, BorderColor))
                    )
                ) { Text("↩ Back to Dongguan", fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp) }
            }
        }
    }
}

// ── Composables ─────────────────────────────────────────────

@Composable
private fun StatusDot(active: Boolean) {
    val color by animateColorAsState(
        targetValue = if (active) Green else WhiteMuted.copy(alpha = 0.4f),
        animationSpec = tween(400), label = "dot"
    )
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun CoordRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = WhiteMuted, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        Text(value, color = WhitePure, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
    }
}

// ── Core logic (suspend) ────────────────────────────────────

private suspend fun getAndSendLocation(
    client: FusedLocationProviderClient,
    http: OkHttpClient,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
    hasPermission: Boolean
): Quadruple? {
    if (!hasPermission) { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return null }
    val loc = runCatching { client.currentLocation() }.getOrNull()
        ?: runCatching { client.lastKnownLocation() }.getOrNull()
        ?: return null

    val response = runCatching {
        http.postJson("""{"lat":${loc.latitude},"lng":${loc.longitude}}""")
    }.getOrElse { "Request failed: ${it.message ?: "unknown error"}" }

    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    return Quadruple(loc.latitude, loc.longitude, response, time)
}

private class Quadruple(val first: Double, val second: Double, val third: String, val fourth: String)

// ── Helpers ─────────────────────────────────────────────────

private fun isServiceRunning(context: Context): Boolean {
    val mgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
    return mgr.getRunningServices(Integer.MAX_VALUE)
        .any { it.service.className == LocationUpdateService::class.java.name }
}

@SuppressLint("MissingPermission")
private suspend fun FusedLocationProviderClient.currentLocation() =
    suspendCancellableCoroutine<android.location.Location?> { cont ->
        getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
            .addOnCanceledListener { cont.resume(null) }
    }

@SuppressLint("MissingPermission")
private suspend fun FusedLocationProviderClient.lastKnownLocation() =
    suspendCancellableCoroutine<android.location.Location?> { cont ->
        lastLocation
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
            .addOnCanceledListener { cont.resume(null) }
    }

private suspend fun OkHttpClient.postJson(json: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(Endpoint)
        .post(json.toRequestBody(JsonMediaType)).build()
    newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty().trim()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.ifBlank { resp.message }}")
        body.ifBlank { "OK" }
    }
}
