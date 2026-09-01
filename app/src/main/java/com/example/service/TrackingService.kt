package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.models.TrackingStatus
import com.example.location.GpsTrackingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var gpsManager: GpsTrackingManager
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "TrackingService"
        const val CHANNEL_ID = "gps_tracking_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "ACTION_STOP"

        fun startService(context: Context) {
            try {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start Foreground TrackingService: ${e.message}")
            }
        }

        fun pauseService(context: Context) {
            try {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = ACTION_PAUSE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not send pause intent to TrackingService: ${e.message}")
            }
        }

        fun resumeService(context: Context) {
            try {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = ACTION_RESUME
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not send resume intent to TrackingService: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not send stop intent to TrackingService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gpsManager = GpsTrackingManager.getInstance(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                gpsManager.startTracking()
            }
            ACTION_PAUSE -> {
                gpsManager.pauseTracking()
                updateNotification()
            }
            ACTION_RESUME -> {
                gpsManager.resumeTracking()
                updateNotification()
            }
            ACTION_STOP -> {
                gpsManager.stopTracking()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    Log.w(TAG, "stopForeground error: ${e.message}")
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        try {
            val notification = buildNotification()
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }

            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException starting foreground service: ${e.message}")
            try {
                // Fallback attempt without location foreground type
                val notification = buildNotification()
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed fallback startForeground: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}")
        }
    }

    private fun observeState() {
        serviceScope.launch {
            gpsManager.trackingState.collectLatest {
                if (it.status != TrackingStatus.STOPPED) {
                    updateNotification()
                }
            }
        }
    }

    private fun updateNotification() {
        try {
            val notification = buildNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun buildNotification(): Notification {
        val state = gpsManager.trackingState.value
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (state.status) {
            TrackingStatus.TRACKING -> "Aktywne śledzenie GPS"
            TrackingStatus.PAUSED -> "Pauza"
            TrackingStatus.STOPPED -> "Zatrzymano"
        }

        val contentText = "Prędkość: %.1f km/h • Dystans: %s • Czas: %s".format(
            state.currentSpeedKmh,
            state.formattedDistance,
            state.formattedDuration
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker: $statusText")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingOpenApp)
            .setOngoing(state.status == TrackingStatus.TRACKING)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Action buttons
        if (state.status == TrackingStatus.TRACKING) {
            val pauseIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_PAUSE }
            val pendingPause = PendingIntent.getService(
                this, 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pauza", pendingPause)
        } else if (state.status == TrackingStatus.PAUSED) {
            val resumeIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_RESUME }
            val pendingResume = PendingIntent.getService(
                this, 2, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Wznów", pendingResume)
        }

        val stopIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 3, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Śledzenie Trasy GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Powiadomienia o bieżącej trasie i prędkości w tle"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
