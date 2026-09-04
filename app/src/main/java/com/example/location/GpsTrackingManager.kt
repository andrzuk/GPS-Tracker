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

    private var lastTrackingLocation: Location? = null
    private var stationaryAnchorLocation: Location? = null
    private var isStationaryLockActive = true
    private var lastLocationProcessedElapsedMillis = 0L
    private var movingDurationSeconds = 0L
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
        private const val EXIT_STATIONARY_SPEED_KMH = 2.2f
        private const val ENTER_STATIONARY_SPEED_KMH = 1.8f
        private const val MIN_EXIT_STATIONARY_DISTANCE_METERS = 5.0
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 1000L
        private const val MAX_VALID_ACCURACY_METERS = 30.0f
        private const val MIN_DISTANCE_ACCUMULATION_METERS = 1.0
        private const val MIN_ROUTE_POINT_DISTANCE_METERS = 3.0

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
                setMinUpdateDistanceMeters(0.5f)
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
                    0.5f,
                    systemLocationListener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MILLIS,
                    0.5f,
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
        lastTrackingLocation = null
        stationaryAnchorLocation = null
        isStationaryLockActive = true
        lastLocationProcessedElapsedMillis = 0L
        movingDurationSeconds = 0L

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
        lastTrackingLocation = null
        stationaryAnchorLocation = null
        isStationaryLockActive = true
    }

    fun resumeTracking() {
        if (_trackingState.value.status != TrackingStatus.PAUSED) return

        _trackingState.update { current ->
            current.copy(status = TrackingStatus.TRACKING)
        }
        lastTrackingLocation = null
        stationaryAnchorLocation = null
        isStationaryLockActive = true
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
        lastTrackingLocation = null
        stationaryAnchorLocation = null
        isStationaryLockActive = true
    }

    fun resetCounters() {
        timerJob?.cancel()
        timerJob = null
        lastTrackingLocation = null
        stationaryAnchorLocation = null
        isStationaryLockActive = true
        movingDurationSeconds = 0L

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
                    if (!isStationaryLockActive && current.currentSpeedKmh >= ENTER_STATIONARY_SPEED_KMH) {
                        movingDurationSeconds++
                    }
                    val effectiveHours = if (movingDurationSeconds > 0) {
                        movingDurationSeconds / 3600.0
                    } else if (newDuration > 0) {
                        newDuration / 3600.0
                    } else {
                        0.0
                    }
                    val newAvgSpeed = if (effectiveHours > 0 && current.distanceMeters > 0) {
                        ((current.distanceMeters / 1000.0) / effectiveHours).toFloat()
                    } else {
                        current.avgSpeedKmh
                    }
                    current.copy(
                        durationSeconds = newDuration,
                        avgSpeedKmh = newAvgSpeed,
                        lastUpdatedTimestamp = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun processNewLocation(location: Location) {
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastLocationProcessedElapsedMillis < 350L) return
        lastLocationProcessedElapsedMillis = nowElapsedMillis

        val accuracy = location.accuracy
        val signalQuality = when {
            accuracy <= 5f -> GpsSignalQuality.EXCELLENT
            accuracy <= 15f -> GpsSignalQuality.GOOD
            accuracy <= 30f -> GpsSignalQuality.WEAK
            else -> GpsSignalQuality.SEARCHING
        }

        // 1. Raw speed evaluation with noise rejection
        val rawSpeedKmh: Float = if (location.hasSpeed()) {
            val speedMps = location.speed
            val isAccurate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                val acc = location.speedAccuracyMetersPerSecond
                speedMps > acc && acc < 1.5f
            } else {
                speedMps >= (ENTER_STATIONARY_SPEED_KMH / 3.6f)
            }
            if (isAccurate) {
                (speedMps * 3.6f).coerceIn(0.0f, MAX_REASONABLE_SPEED_KMH)
            } else {
                0.0f
            }
        } else {
            0.0f
        }

        val point = LocationPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = rawSpeedKmh,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            timestamp = location.time,
            accuracy = accuracy
        )

        // Always update map marker position & signal quality
        if (_trackingState.value.status != TrackingStatus.TRACKING) {
            _trackingState.update { current ->
                current.copy(
                    currentLocation = point,
                    gpsAccuracyMeters = accuracy,
                    signalQuality = signalQuality,
                    altitudeMeters = point.altitude,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            return
        }

        // If accuracy is poor (e.g. searching indoors), update marker but don't accumulate distance
        if (accuracy > MAX_VALID_ACCURACY_METERS) {
            _trackingState.update { current ->
                current.copy(
                    currentLocation = point,
                    gpsAccuracyMeters = accuracy,
                    signalQuality = signalQuality,
                    altitudeMeters = point.altitude,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            return
        }

        _trackingState.update { current ->
            if (current.status != TrackingStatus.TRACKING) return@update current

            // 2. Stationary Lock (Deadband) state machine
            val currentAnchor = stationaryAnchorLocation ?: location.also { stationaryAnchorLocation = it }
            val distanceFromAnchor = location.distanceTo(currentAnchor).toDouble()
            val minExitDistance = max(MIN_EXIT_STATIONARY_DISTANCE_METERS, (accuracy * 0.75).toDouble())

            if (isStationaryLockActive) {
                // Must have both real forward speed and physical displacement exceeding the cluster radius
                val hasBrokenOut = rawSpeedKmh >= EXIT_STATIONARY_SPEED_KMH && distanceFromAnchor >= minExitDistance
                if (hasBrokenOut) {
                    isStationaryLockActive = false
                    lastTrackingLocation = currentAnchor
                } else {
                    // Update anchor gently if drift is very close to keep centroid accurate
                    if (distanceFromAnchor < minExitDistance * 0.4) {
                        stationaryAnchorLocation = location
                    }
                }
            } else {
                // Currently moving: check if stopped
                if (rawSpeedKmh < ENTER_STATIONARY_SPEED_KMH) {
                    isStationaryLockActive = true
                    stationaryAnchorLocation = location
                    lastTrackingLocation = location
                }
            }

            // 3. Effective speed calculation
            val effectiveSpeed = if (isStationaryLockActive) {
                0.0f
            } else {
                if (current.currentSpeedKmh <= 0.001f) {
                    rawSpeedKmh
                } else {
                    current.currentSpeedKmh * (1.0f - SPEED_SMOOTHING_FACTOR) +
                        rawSpeedKmh * SPEED_SMOOTHING_FACTOR
                }
            }

            // 4. Distance calculation
            var distanceDelta = 0.0
            var elevationDelta = 0.0
            val lastLoc = lastTrackingLocation

            if (!isStationaryLockActive && effectiveSpeed > 0.0f) {
                if (lastLoc == null) {
                    lastTrackingLocation = location
                } else {
                    val stepDist = location.distanceTo(lastLoc).toDouble()
                    val dtSec = (location.time - lastLoc.time) / 1000.0
                    val maxPlausibleDist = if (dtSec > 0) (dtSec * (MAX_REASONABLE_SPEED_KMH / 3.6)) + 5.0 else 50.0

                    if (stepDist <= maxPlausibleDist) {
                        if (stepDist >= MIN_DISTANCE_ACCUMULATION_METERS) {
                            distanceDelta = stepDist
                            lastTrackingLocation = location

                            if (location.hasAltitude() && lastLoc.hasAltitude()) {
                                val diffAlt = location.altitude - lastLoc.altitude
                                if (diffAlt in 0.5..50.0) {
                                    elevationDelta = diffAlt
                                }
                            }
                        }
                    } else {
                        // Unrealistic jump - re-anchor
                        lastTrackingLocation = location
                    }
                }
            }

            val newDistance = current.distanceMeters + distanceDelta
            val newMaxSpeed = max(current.maxSpeedKmh, effectiveSpeed)
            val newElevation = current.elevationGainMeters + elevationDelta

            // 5. Route points: append if first point or if moved at least 3m from last point
            val updatedPoints = if (current.routePoints.isEmpty()) {
                listOf(point)
            } else if (!isStationaryLockActive && effectiveSpeed > 0.0f) {
                val lastPoint = current.routePoints.last()
                val distFromLastPoint = FloatArray(1)
                Location.distanceBetween(
                    lastPoint.latitude, lastPoint.longitude,
                    point.latitude, point.longitude,
                    distFromLastPoint
                )
                if (distFromLastPoint[0] >= MIN_ROUTE_POINT_DISTANCE_METERS) {
                    current.routePoints + point
                } else {
                    current.routePoints
                }
            } else {
                current.routePoints
            }

            current.copy(
                currentSpeedKmh = effectiveSpeed,
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
