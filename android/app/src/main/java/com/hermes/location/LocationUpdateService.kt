package com.hermes.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class LocationUpdateService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lat = loc.latitude
            lng = loc.longitude
            updateTimestamp()
            saveToPrefs()
            sendToServer(loc.latitude, loc.longitude)
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting...", "--", "--"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                updateNotification()
            }
            else -> {
                startLocationUpdates()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                UPDATE_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
                .setMaxUpdateDelayMillis(MAX_DELAY_MS)
                .build()

            fusedLocationRequest = request

            fusedLocationClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            )

            // Also get an immediate location
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        lat = location.latitude
                        lng = location.longitude
                        updateTimestamp()
                        saveToPrefs()
                        sendToServer(location.latitude, location.longitude)
                        updateNotification()
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
            stopSelf()
        }
    }

    private fun sendToServer(latitude: Double, longitude: Double) {
        thread {
            try {
                val json = """{"lat":$latitude,"lng":$longitude}"""
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty().trim()
                    serverResponse = if (response.isSuccessful) body.ifBlank { "OK" }
                    else "HTTP ${response.code}: ${body.ifBlank { response.message }}"
                }
            } catch (e: IOException) {
                serverResponse = "Error: ${e.message}"
            }
        }
    }

    private fun updateNotification() {
        val notification = buildNotification(
            serverResponse,
            lat?.let { String.format("%.6f", it) } ?: "--",
            lng?.let { String.format("%.6f", it) } ?: "--"
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(city: String, latStr: String, lngStr: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationUpdateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 Hermes Location Active")
            .setContentText("$city · $latStr, $lngStr")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background location tracking for Hermes"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun updateTimestamp() {
        lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putFloat(PREF_LAT, lat?.toFloat() ?: 0f)
            .putFloat(PREF_LNG, lng?.toFloat() ?: 0f)
            .putString(PREF_TIME, lastUpdateTime)
            .putString(PREF_RESPONSE, serverResponse)
            .apply()
    }

    companion object {
        private const val TAG = "LocUpdateService"
        private const val CHANNEL_ID = "hermes_location_service"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 120_000L // 2 minutes
        private const val FASTEST_INTERVAL_MS = 60_000L
        private const val MAX_DELAY_MS = 300_000L // 5 min max delay
        private const val ENDPOINT = "https://location.lzghs.top:16666/location"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal const val PREFS_NAME = "hermes_location_prefs"
        internal const val PREF_LAT = "service_lat"
        internal const val PREF_LNG = "service_lng"
        internal const val PREF_TIME = "service_last_time"
        internal const val PREF_RESPONSE = "service_response"
        internal const val ACTION_STOP = "com.hermes.location.STOP_SERVICE"
        internal const val ACTION_UPDATE_NOTIFICATION = "com.hermes.location.UPDATE_NOTIFICATION"

        // Shared state for the Activity to read
        internal var lat: Double? = null
        internal var lng: Double? = null
        internal var lastUpdateTime: String = "--:--:--"
        internal var serverResponse: String = "Background service idle"

        @Volatile
        internal var fusedLocationRequest: LocationRequest? = null
    }
}
