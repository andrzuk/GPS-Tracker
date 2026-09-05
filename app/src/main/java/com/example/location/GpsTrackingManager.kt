package com.example.location

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.data.models.GpsSignalQuality
import com.example.data.models.LocationPoint
import com.example.data.models.TrackingState
import com.example.data.models.TrackingStatus
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class GpsTrackingManager private constructor(private val context: Context) {

    private val TAG = "GpsTrackingManager"

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val systemLocationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private val _trackingState = MutableStateFlow(TrackingState())
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null

    private var lastRawLocation: Location? = null
    private var lastDistanceLocation: Location? = null
    private var lastLocationProcessedElapsedMillis = 0L
    private var isListeningGps = false
    private var isSystemFallbackActive = false

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                processNewLocation(location)
            }
        }
    }

    private val systemLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processNewLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    companion object {
        private const val MAX_REASONABLE_SPEED_KMH = 160.0f
        private const val SPEED_SMOOTHING_FACTOR = 0.35f
        private const val STATIONARY_SPEED_THRESHOLD_KMH = 0.5f
        private const val MOVEMENT_SPEED_THRESHOLD_KMH = 1.0f
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 500L

        @Volatile
        private var instance: GpsTrackingManager? = null

        fun getInstance(context: Context): GpsTrackingManager {
            return instance ?: synchronized(this) {
                instance ?: GpsTrackingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startGpsUpdates() {
        if (isListeningGps) return
        isListeningGps = true

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_UPDATE_INTERVAL_MILLIS
            ).apply {
                setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MILLIS)
                setMinUpdateDistanceMeters(0.0f)
                setWaitForAccurateLocation(false)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                fusedLocationCallback,
                Looper.getMainLooper()
            ).addOnFailureListener { e ->
                Log.w(TAG, "Fused location request failed, using system fallback: ${e.message}")
                requestSystemLocationFallback()
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _trackingState.value.currentLocation == null) {
                    processNewLocation(loc)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in fusedLocationClient: ${e.message}")
            _trackingState.update { it.copy(signalQuality = GpsSignalQuality.DISCONNECTED) }
        } catch (e: Exception) {
            Log.w(TAG, "Exception in fusedLocationClient: ${e.message}, trying system location manager")
            requestSystemLocationFallback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSystemLocationFallback() {
        if (isSystemFallbackActive) return
        val lm = systemLocationManager ?: return
        try {
            isSystemFallbackActive = true
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MILLIS,
                    0.0f,
                    systemLocationListener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MILLIS,
                    0.0f,
                    systemLocationListener,
                    Looper.getMainLooper()
                )
            }
            val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = lastGps ?: lastNet
            if (best != null && _trackingState.value.currentLocation == null) {
                processNewLocation(best)
            }
        } catch (e: SecurityException) {
            isSystemFallbackActive = false
            Log.w(TAG, "SecurityException in system LocationManager: ${e.message}")
        } catch (e: Exception) {
            isSystemFallbackActive = false
            Log.w(TAG, "Exception in system LocationManager: ${e.message}")
        }
    }

    fun stopGpsUpdates() {
        if (!isListeningGps) return
        isListeningGps = false
        isSystemFallbackActive = false
        try {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
        } catch (e: Exception) {
            Log.w(TAG, "removeLocationUpdates error: ${e.message}")
        }
        try {
            systemLocationManager?.removeUpdates(systemLocationListener)
        } catch (e: Exception) {
            Log.w(TAG, "removeUpdates system error: ${e.message}")
        }
    }

    fun startTracking() {
        if (_trackingState.value.status == TrackingStatus.TRACKING) return

        timerJob?.cancel()
        timerJob = null
        lastRawLocation = null
        lastDistanceLocation = null
        lastLocationProcessedElapsedMillis = 0L

        val currentLoc = _trackingState.value.currentLocation

        _trackingState.value = TrackingState(
            status = TrackingStatus.TRACKING,
            currentSpeedKmh = 0.0f,
            avgSpeedKmh = 0.0f,
            maxSpeedKmh = 0.0f,
            distanceMeters = 0.0,
            durationSeconds = 0L,
            currentLocation = currentLoc,
            routePoints = if (currentLoc != null) listOf(currentLoc) else emptyList(),
            gpsAccuracyMeters = _trackingState.value.gpsAccuracyMeters,
            signalQuality = _trackingState.value.signalQuality,
            altitudeMeters = currentLoc?.altitude ?: 0.0,
            elevationGainMeters = 0.0,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        startGpsUpdates()
        startTimer()
    }

    fun pauseTracking() {
        if (_trackingState.value.status != TrackingStatus.TRACKING) return

        _trackingState.update { current ->
            current.copy(
                status = TrackingStatus.PAUSED,
                currentSpeedKmh = 0.0f
            )
        }
        timerJob?.cancel()
        timerJob = null
        stopGpsUpdates()
        lastRawLocation = null
        lastDistanceLocation = null
    }

    fun resumeTracking() {
        if (_trackingState.value.status != TrackingStatus.PAUSED) return

        _trackingState.update { current ->
            current.copy(status = TrackingStatus.TRACKING)
        }
        startGpsUpdates()
        startTimer()
    }

    fun stopTracking() {
        _trackingState.update { current ->
            current.copy(
                status = TrackingStatus.STOPPED,
                currentSpeedKmh = 0.0f
            )
        }
        timerJob?.cancel()
        timerJob = null
        stopGpsUpdates()
        lastRawLocation = null
        lastDistanceLocation = null
    }

    fun resetCounters() {
        timerJob?.cancel()
        timerJob = null
        lastRawLocation = null
        lastDistanceLocation = null

        val currentLoc = _trackingState.value.currentLocation

        _trackingState.value = TrackingState(
            status = TrackingStatus.STOPPED,
            currentSpeedKmh = 0.0f,
            avgSpeedKmh = 0.0f,
            maxSpeedKmh = 0.0f,
            distanceMeters = 0.0,
            durationSeconds = 0L,
            currentLocation = currentLoc,
            routePoints = if (currentLoc != null) listOf(currentLoc) else emptyList(),
            gpsAccuracyMeters = _trackingState.value.gpsAccuracyMeters,
            signalQuality = _trackingState.value.signalQuality,
            altitudeMeters = currentLoc?.altitude ?: 0.0,
            elevationGainMeters = 0.0,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                _trackingState.update { current ->
                    if (current.status != TrackingStatus.TRACKING) return@update current
                    val newDuration = current.durationSeconds + 1
                    val newAvgSpeed = if (newDuration > 0 && current.distanceMeters > 0) {
                        val hours = newDuration / 3600.0
                        val km = current.distanceMeters / 1000.0
                        (km / hours).toFloat()
                    } else {
                        current.avgSpeedKmh
                    }
                    val timeSinceLastFix = SystemClock.elapsedRealtime() - lastLocationProcessedElapsedMillis
                    val speedWatchdog = if (lastLocationProcessedElapsedMillis > 0L && timeSinceLastFix > 1500L) {
                        0.0f
                    } else {
                        current.currentSpeedKmh
                    }
                    current.copy(
                        durationSeconds = newDuration,
                        avgSpeedKmh = newAvgSpeed,
                        currentSpeedKmh = speedWatchdog,
                        lastUpdatedTimestamp = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun processNewLocation(location: Location) {
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastLocationProcessedElapsedMillis < LOCATION_UPDATE_INTERVAL_MILLIS) return
        lastLocationProcessedElapsedMillis = nowElapsedMillis

        val accuracy = location.accuracy
        val signalQuality = when {
            accuracy <= 5f -> GpsSignalQuality.EXCELLENT
            accuracy <= 15f -> GpsSignalQuality.GOOD
            accuracy <= 30f -> GpsSignalQuality.WEAK
            else -> GpsSignalQuality.SEARCHING
        }
        val reportedSpeedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).coerceIn(0.0f, MAX_REASONABLE_SPEED_KMH)
        } else {
            0.0f
        }
        val hasReliableReportedSpeed = location.hasSpeed() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !location.hasSpeedAccuracy() ||
                reportedSpeedKmh - location.speedAccuracyMetersPerSecond * 3.6f >
                    STATIONARY_SPEED_THRESHOLD_KMH)

        val point = LocationPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = reportedSpeedKmh,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            timestamp = location.time,
            accuracy = accuracy
        )

        _trackingState.update { current ->
            if (current.status != TrackingStatus.TRACKING) {
                // Just update current position and signal
                return@update current.copy(
                    currentLocation = point,
                    gpsAccuracyMeters = accuracy,
                    signalQuality = signalQuality,
                    altitudeMeters = point.altitude,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }

            val lastLoc = lastRawLocation
            val lastDistanceLoc = lastDistanceLocation
            var distanceDelta = 0.0
            var elevationDelta = 0.0
            var calculatedSpeedKmh = 0.0f

            if (lastLoc != null) {
                val dist = location.distanceTo(lastLoc).toDouble()
                val elapsedMillis = location.time - lastLoc.time
                val maximumPlausibleDistance = elapsedMillis * 55.56 / 1000.0
                val minimumSpeedCalculationDistance = max(
                    3.0,
                    max(location.accuracy, lastLoc.accuracy).toDouble()
                )

                if (
                    elapsedMillis > 0 &&
                    dist >= minimumSpeedCalculationDistance &&
                    dist <= maximumPlausibleDistance
                ) {
                    distanceDelta = dist
                    calculatedSpeedKmh = (dist / elapsedMillis * 3600.0).toFloat()
                    if (location.hasAltitude() && lastLoc.hasAltitude()) {
                        val diffAlt = location.altitude - lastLoc.altitude
                        if (diffAlt > 0.5) {
                            elevationDelta = diffAlt
                        }
                    }
                }
            }
            if (lastDistanceLoc != null) {
                val distanceFromLastAcceptedPoint = location.distanceTo(lastDistanceLoc).toDouble()
                val minimumReliableDistance = if (hasReliableReportedSpeed) {
                    1.0
                } else {
                    max(
                        3.0,
                        max(location.accuracy, lastDistanceLoc.accuracy).toDouble()
                    )
                }

                if (distanceFromLastAcceptedPoint >= minimumReliableDistance) {
                    distanceDelta = distanceFromLastAcceptedPoint
                }
            }
            lastRawLocation = location

            val measuredSpeedKmh = if (hasReliableReportedSpeed) {
                reportedSpeedKmh
            } else if (!location.hasSpeed()) {
                calculatedSpeedKmh
            } else {
                0.0f
            }
            val effectiveSpeed = when {
                measuredSpeedKmh <= STATIONARY_SPEED_THRESHOLD_KMH -> 0.0f
                current.currentSpeedKmh <= 0.001f &&
                    measuredSpeedKmh < MOVEMENT_SPEED_THRESHOLD_KMH -> 0.0f
                current.currentSpeedKmh <= 0.001f -> measuredSpeedKmh
                measuredSpeedKmh <= MOVEMENT_SPEED_THRESHOLD_KMH -> 0.0f
                else -> {
                    val smoothed = current.currentSpeedKmh * (1.0f - SPEED_SMOOTHING_FACTOR) +
                        measuredSpeedKmh * SPEED_SMOOTHING_FACTOR
                    if (smoothed <= MOVEMENT_SPEED_THRESHOLD_KMH) 0.0f else smoothed
                }
            }
            val newDistance = if (effectiveSpeed > 0.0f) {
                current.distanceMeters + distanceDelta
            } else {
                current.distanceMeters
            }
            if (effectiveSpeed > 0.0f && distanceDelta > 0.0) {
                lastDistanceLocation = location
            } else if (lastDistanceLocation == null) {
                lastDistanceLocation = location
            }
            val newMaxSpeed = max(current.maxSpeedKmh, effectiveSpeed)

            val newAvgSpeed = if (current.durationSeconds > 0 && newDistance > 0) {
                val hours = current.durationSeconds / 3600.0
                val km = newDistance / 1000.0
                (km / hours).toFloat()
            } else {
                0.0f
            }

            val newElevation = current.elevationGainMeters + elevationDelta
            val updatedPoints = current.routePoints + point

            current.copy(
                currentSpeedKmh = effectiveSpeed,
                avgSpeedKmh = newAvgSpeed,
                maxSpeedKmh = newMaxSpeed,
                distanceMeters = newDistance,
                currentLocation = point,
                routePoints = updatedPoints,
                gpsAccuracyMeters = accuracy,
                signalQuality = signalQuality,
                altitudeMeters = point.altitude,
                elevationGainMeters = newElevation,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        }
    }
}
