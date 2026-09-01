package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class GpsTrackingManager private constructor(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _trackingState = MutableStateFlow(TrackingState())
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null

    private var lastRawLocation: Location? = null
    private var isListeningGps = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                processNewLocation(location)
            }
        }
    }

    companion object {
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

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).apply {
            setMinUpdateIntervalMillis(500L)
            setMinUpdateDistanceMeters(0.5f)
            setWaitForAccurateLocation(false)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            // Fetch last known position immediately
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _trackingState.value.currentLocation == null) {
                    processNewLocation(loc)
                }
            }
        } catch (e: SecurityException) {
            _trackingState.update { it.copy(signalQuality = GpsSignalQuality.DISCONNECTED) }
        }
    }

    fun stopGpsUpdates() {
        if (!isListeningGps) return
        isListeningGps = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun startTracking() {
        if (_trackingState.value.status == TrackingStatus.TRACKING) return

        _trackingState.update { current ->
            current.copy(status = TrackingStatus.TRACKING)
        }
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
    }

    fun resumeTracking() {
        if (_trackingState.value.status != TrackingStatus.PAUSED) return

        _trackingState.update { current ->
            current.copy(status = TrackingStatus.TRACKING)
        }
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
    }

    fun resetCounters() {
        timerJob?.cancel()
        timerJob = null
        lastRawLocation = null

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
        val accuracy = location.accuracy
        val signalQuality = when {
            accuracy <= 5f -> GpsSignalQuality.EXCELLENT
            accuracy <= 15f -> GpsSignalQuality.GOOD
            accuracy <= 30f -> GpsSignalQuality.WEAK
            else -> GpsSignalQuality.SEARCHING
        }

        // Calculate speed in km/h
        val rawSpeedKmh = if (location.hasSpeed() && location.speed >= 0) {
            location.speed * 3.6f
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
            var distanceDelta = 0.0
            var elevationDelta = 0.0

            if (lastLoc != null) {
                val dist = location.distanceTo(lastLoc).toDouble()

                // Filter out small jitter (<1m with low speed) or teleport jumps
                if (dist > 1.2 && dist < 200.0) {
                    distanceDelta = dist
                    if (location.hasAltitude() && lastLoc.hasAltitude()) {
                        val diffAlt = location.altitude - lastLoc.altitude
                        if (diffAlt > 0.5) {
                            elevationDelta = diffAlt
                        }
                    }
                }
            }
            lastRawLocation = location

            val newDistance = current.distanceMeters + distanceDelta
            val effectiveSpeed = if (rawSpeedKmh > 0.8f) rawSpeedKmh else 0.0f
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
