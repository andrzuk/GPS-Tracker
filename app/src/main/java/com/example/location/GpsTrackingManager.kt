package com.example.location

import android.annotation.SuppressLint
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
import kotlinx.collections.immutable.persistentListOf
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
    private var lastProcessedLocationElapsedNanos = 0L
    private var lastProcessedLocationTimeMillis = 0L
    private var isListeningGps = false
    private var isSystemFallbackActive = false
    private var consecutiveMovingFixes = 0

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
        private const val ACCELERATION_SMOOTHING_FACTOR = 0.60f
        private const val DECELERATION_SMOOTHING_FACTOR = 0.80f
        private const val STOPPING_DECAY_FACTOR = 0.85f
        private const val BASE_STATIONARY_SPEED_THRESHOLD_KMH = 1.8f
        private const val IMMEDIATE_ZERO_THRESHOLD_KMH = 8.0f
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 500L
        private const val WATCHDOG_STALE_DECAY_TIMEOUT_MILLIS = 1500L
        private const val WATCHDOG_ZERO_TIMEOUT_MILLIS = 2500L

        /**
         * Adaptive noise gate for stationary detection.
         * Indoors and in areas with poor GPS accuracy, multipath reflections bouncing off walls
         * cause Doppler and coordinate drift of 1.5 - 4.5 km/h while stationary.
         */
        private fun getStationarySpeedThreshold(accuracyMeters: Float): Float {
            return when {
                accuracyMeters <= 5.0f -> 1.5f
                accuracyMeters <= 10.0f -> 2.0f
                accuracyMeters <= 20.0f -> 3.2f
                accuracyMeters <= 35.0f -> 4.5f
                else -> 6.0f
            }
        }

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
        lastProcessedLocationElapsedNanos = 0L
        lastProcessedLocationTimeMillis = 0L
        consecutiveMovingFixes = 0

        val currentLoc = _trackingState.value.currentLocation

        _trackingState.value = TrackingState(
            status = TrackingStatus.TRACKING,
            currentSpeedKmh = 0.0f,
            avgSpeedKmh = 0.0f,
            maxSpeedKmh = 0.0f,
            distanceMeters = 0.0,
            durationSeconds = 0L,
            currentLocation = currentLoc,
            routePoints = if (currentLoc != null) persistentListOf(currentLoc) else persistentListOf(),
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
        lastProcessedLocationElapsedNanos = 0L
        lastProcessedLocationTimeMillis = 0L
        consecutiveMovingFixes = 0
    }

    fun resumeTracking() {
        if (_trackingState.value.status != TrackingStatus.PAUSED) return

        _trackingState.update { current ->
            current.copy(status = TrackingStatus.TRACKING)
        }
        consecutiveMovingFixes = 0
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
        lastProcessedLocationElapsedNanos = 0L
        lastProcessedLocationTimeMillis = 0L
        consecutiveMovingFixes = 0
    }

    fun resetCounters() {
        timerJob?.cancel()
        timerJob = null
        lastRawLocation = null
        lastDistanceLocation = null
        lastProcessedLocationElapsedNanos = 0L
        lastProcessedLocationTimeMillis = 0L
        consecutiveMovingFixes = 0

        val currentLoc = _trackingState.value.currentLocation

        _trackingState.value = TrackingState(
            status = TrackingStatus.STOPPED,
            currentSpeedKmh = 0.0f,
            avgSpeedKmh = 0.0f,
            maxSpeedKmh = 0.0f,
            distanceMeters = 0.0,
            durationSeconds = 0L,
            currentLocation = currentLoc,
            routePoints = if (currentLoc != null) persistentListOf(currentLoc) else persistentListOf(),
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
                    val speedWatchdog = when {
                        current.signalQuality == GpsSignalQuality.SEARCHING && current.currentSpeedKmh > 0.0f -> {
                            if (current.currentSpeedKmh <= IMMEDIATE_ZERO_THRESHOLD_KMH) {
                                0.0f
                            } else {
                                val decayed = current.currentSpeedKmh * (1.0f - STOPPING_DECAY_FACTOR)
                                if (decayed <= BASE_STATIONARY_SPEED_THRESHOLD_KMH) 0.0f else decayed
                            }
                        }
                        lastLocationProcessedElapsedMillis > 0L && timeSinceLastFix >= WATCHDOG_ZERO_TIMEOUT_MILLIS -> 0.0f
                        lastLocationProcessedElapsedMillis > 0L && timeSinceLastFix >= WATCHDOG_STALE_DECAY_TIMEOUT_MILLIS -> {
                            if (current.currentSpeedKmh <= IMMEDIATE_ZERO_THRESHOLD_KMH) {
                                0.0f
                            } else {
                                val decayed = current.currentSpeedKmh * (1.0f - STOPPING_DECAY_FACTOR)
                                if (decayed <= BASE_STATIONARY_SPEED_THRESHOLD_KMH) 0.0f else decayed
                            }
                        }
                        else -> current.currentSpeedKmh
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
        val hasElapsedRealtime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
            location.elapsedRealtimeNanos > 0L

        val shouldSkip = if (hasElapsedRealtime && lastProcessedLocationElapsedNanos > 0L) {
            val deltaMillis = (location.elapsedRealtimeNanos - lastProcessedLocationElapsedNanos) / 1_000_000L
            deltaMillis < LOCATION_UPDATE_INTERVAL_MILLIS
        } else if (location.time > 0L && lastProcessedLocationTimeMillis > 0L) {
            val deltaMillis = location.time - lastProcessedLocationTimeMillis
            deltaMillis < LOCATION_UPDATE_INTERVAL_MILLIS
        } else {
            false
        }

        if (shouldSkip) return

        if (hasElapsedRealtime) {
            lastProcessedLocationElapsedNanos = location.elapsedRealtimeNanos
        }
        if (location.time > 0L) {
            lastProcessedLocationTimeMillis = location.time
        }
        lastLocationProcessedElapsedMillis = SystemClock.elapsedRealtime()

        val accuracy = location.accuracy
        val isNetworkProvider = location.provider == LocationManager.NETWORK_PROVIDER
        val signalQuality = when {
            isNetworkProvider || accuracy > 20f -> GpsSignalQuality.SEARCHING
            accuracy <= 5f -> GpsSignalQuality.EXCELLENT
            accuracy <= 12f -> GpsSignalQuality.GOOD
            else -> GpsSignalQuality.WEAK
        }
        val isSearchingGps = signalQuality == GpsSignalQuality.SEARCHING || accuracy > 20f
        val stationaryThreshold = getStationarySpeedThreshold(accuracy)

        // Raw hardware Doppler speed from satellite carrier phase
        val rawReportedSpeedKmh = if (location.hasSpeed() && !isNetworkProvider) {
            (location.speed * 3.6f).coerceIn(0.0f, MAX_REASONABLE_SPEED_KMH)
        } else {
            0.0f
        }
        val speedAccuracyKmh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond * 3.6f
        } else {
            0.0f
        }

        // Hardware Doppler speed is considered reliable ONLY if accuracy is good and exceeds sensor noise
        val hasReliableReportedSpeed = !isSearchingGps && location.hasSpeed() && run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !location.hasSpeedAccuracy()) {
                rawReportedSpeedKmh > stationaryThreshold
            } else {
                speedAccuracyKmh < 8.0f && rawReportedSpeedKmh > max(stationaryThreshold, speedAccuracyKmh * 1.25f)
            }
        }
        val filteredReportedSpeedKmh = if (hasReliableReportedSpeed) rawReportedSpeedKmh else 0.0f

        val point = LocationPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = if (isSearchingGps) 0.0f else filteredReportedSpeedKmh,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            timestamp = location.time,
            accuracy = accuracy
        )

        _trackingState.update { current ->
            if (current.status != TrackingStatus.TRACKING) {
                consecutiveMovingFixes = 0
                return@update current.copy(
                    currentLocation = point,
                    currentSpeedKmh = 0.0f,
                    gpsAccuracyMeters = accuracy,
                    signalQuality = signalQuality,
                    altitudeMeters = point.altitude,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }

            val lastLoc = lastRawLocation
            var calculatedSpeedKmh = 0.0f

            // Calculate speed from coordinates ONLY if device lacks hardware Doppler (hasSpeed == false)
            // and GPS fix is very clean (accuracy <= 10m).
            // When location.hasSpeed() is true, the GPS chip's Doppler measurement is vastly superior to coordinate differences.
            if (!isSearchingGps && !location.hasSpeed() && lastLoc != null && accuracy <= 10f && lastLoc.accuracy <= 10f) {
                val dist = location.distanceTo(lastLoc).toDouble()
                val elapsedMillis = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
                    location.elapsedRealtimeNanos > 0L &&
                    lastLoc.elapsedRealtimeNanos > 0L
                ) {
                    (location.elapsedRealtimeNanos - lastLoc.elapsedRealtimeNanos) / 1_000_000L
                } else {
                    location.time - lastLoc.time
                }
                val maximumPlausibleDistance = elapsedMillis * 55.56 / 1000.0

                if (elapsedMillis in 400..3000 && dist >= 5.0 && dist <= maximumPlausibleDistance) {
                    val calc = (dist / elapsedMillis * 3600.0).toFloat()
                    if (calc > stationaryThreshold && calc <= 30.0f) {
                        calculatedSpeedKmh = calc
                    }
                }
            }

            // Measured speed:
            // 1. If searching GPS or network provider -> 0.0
            // 2. If hardware Doppler is available:
            //    - If reliable reported speed > stationaryThreshold -> filteredReportedSpeedKmh
            //    - Otherwise (Doppler says speed <= threshold) -> 0.0 (STATIONARY! Never override Doppler with coordinate jumps!)
            // 3. If hardware Doppler is absent and clean calculated speed exists -> calculatedSpeedKmh
            // 4. Otherwise -> 0.0
            val measuredSpeedKmh = when {
                isSearchingGps -> 0.0f
                location.hasSpeed() -> if (hasReliableReportedSpeed) filteredReportedSpeedKmh else 0.0f
                calculatedSpeedKmh > stationaryThreshold -> calculatedSpeedKmh
                else -> 0.0f
            }

            // Track consecutive moving fixes to eliminate single-sample multipath glitches
            if (measuredSpeedKmh > stationaryThreshold) {
                consecutiveMovingFixes++
            } else {
                consecutiveMovingFixes = 0
            }

            // Smoothing filter
            val effectiveSpeed = when {
                isSearchingGps && current.currentSpeedKmh <= IMMEDIATE_ZERO_THRESHOLD_KMH -> 0.0f
                measuredSpeedKmh <= 0.001f -> {
                    // Vehicle / device is stationary
                    if (current.currentSpeedKmh <= IMMEDIATE_ZERO_THRESHOLD_KMH) {
                        0.0f
                    } else {
                        val decayed = current.currentSpeedKmh * (1.0f - STOPPING_DECAY_FACTOR)
                        if (decayed <= stationaryThreshold) 0.0f else decayed
                    }
                }
                current.currentSpeedKmh <= 0.001f && measuredSpeedKmh <= stationaryThreshold -> 0.0f
                current.currentSpeedKmh <= 0.001f -> {
                    // Starting from stop: require at least 2 consecutive moving fixes or speed > 3.0 km/h
                    if (consecutiveMovingFixes >= 2 || measuredSpeedKmh > 3.0f) {
                        measuredSpeedKmh
                    } else {
                        0.0f
                    }
                }
                measuredSpeedKmh < current.currentSpeedKmh -> {
                    // Decelerating / braking: highly responsive
                    val factor = if (current.currentSpeedKmh - measuredSpeedKmh > 15.0f) {
                        0.90f
                    } else {
                        DECELERATION_SMOOTHING_FACTOR
                    }
                    val smoothed = current.currentSpeedKmh * (1.0f - factor) +
                        measuredSpeedKmh * factor
                    if (smoothed <= stationaryThreshold) 0.0f else smoothed
                }
                else -> {
                    // Accelerating or cruising: smooth display without micro-jitter
                    val smoothed = current.currentSpeedKmh * (1.0f - ACCELERATION_SMOOTHING_FACTOR) +
                        measuredSpeedKmh * ACCELERATION_SMOOTHING_FACTOR
                    if (smoothed <= stationaryThreshold) 0.0f else smoothed
                }
            }

            // Distance calculation:
            // CRITICAL: A device that is stationary (measuredSpeedKmh == 0 or effectiveSpeed == 0)
            // or has poor GPS accuracy (> 20m) MUST NEVER accumulate distance!
            var distanceDelta = 0.0
            var elevationDelta = 0.0
            var hasAcceptedMovementSegment = false
            var distanceFromAnchor = 0.0
            var maximumPlausibleDistanceFromAnchor = 0.0

            val distanceAnchor = lastDistanceLocation ?: if (!isSearchingGps) lastLoc else null

            if (!isSearchingGps && distanceAnchor != null && distanceAnchor.accuracy <= 20f && accuracy <= 20f) {
                val elapsedFromAnchorMillis = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
                    location.elapsedRealtimeNanos > 0L &&
                    distanceAnchor.elapsedRealtimeNanos > 0L
                ) {
                    (location.elapsedRealtimeNanos - distanceAnchor.elapsedRealtimeNanos) / 1_000_000L
                } else {
                    location.time - distanceAnchor.time
                }

                if (elapsedFromAnchorMillis > 0) {
                    distanceFromAnchor = location.distanceTo(distanceAnchor).toDouble()
                    maximumPlausibleDistanceFromAnchor = elapsedFromAnchorMillis * 55.56 / 1000.0
                    val combinedAccuracyMargin = (location.accuracy + distanceAnchor.accuracy).toDouble() * 0.75
                    val minimumReliableDistance = max(6.0, combinedAccuracyMargin)

                    // Movement evidence requires actual speed and confirmed consecutive moving fixes!
                    val hasTrueMovementEvidence = consecutiveMovingFixes >= 2 &&
                        measuredSpeedKmh > stationaryThreshold &&
                        effectiveSpeed > stationaryThreshold

                    if (
                        hasTrueMovementEvidence &&
                        distanceFromAnchor >= minimumReliableDistance &&
                        distanceFromAnchor <= maximumPlausibleDistanceFromAnchor
                    ) {
                        hasAcceptedMovementSegment = true
                        distanceDelta = distanceFromAnchor
                        if (location.hasAltitude() && distanceAnchor.hasAltitude()) {
                            val diffAlt = location.altitude - distanceAnchor.altitude
                            if (diffAlt > 0.5) {
                                elevationDelta = diffAlt
                            }
                        }
                    }
                }
            }

            lastRawLocation = location

            val newDistance = if (hasAcceptedMovementSegment) {
                current.distanceMeters + distanceDelta
            } else {
                current.distanceMeters
            }

            // Anchor management:
            // When moving, move anchor forward on accepted segment.
            // When stationary, keep anchor updated to the current location so slow multi-minute drift never accumulates!
            if (hasAcceptedMovementSegment) {
                lastDistanceLocation = location
            } else if (measuredSpeedKmh <= 0.001f || isSearchingGps) {
                // Keep anchor updated while stationary so indoor jitter cannot build up against a stale anchor!
                lastDistanceLocation = location
            } else if (lastDistanceLocation == null && !isSearchingGps) {
                lastDistanceLocation = location
            } else if (
                maximumPlausibleDistanceFromAnchor > 0.0 &&
                distanceFromAnchor > maximumPlausibleDistanceFromAnchor * 1.5 &&
                !isSearchingGps
            ) {
                lastDistanceLocation = location
            }

            // Max speed update:
            // ONLY update max speed if:
            // 1. Not searching GPS and accuracy is clean (<= 15m)
            // 2. Hardware Doppler speed is confirmed (hasReliableReportedSpeed)
            // 3. Sustained across consecutive moving fixes (consecutiveMovingFixes >= 2)
            // 4. effectiveSpeed (the actual speed displayed on the gauge) exceeds previous max
            val isEligibleForMaxSpeed = !isSearchingGps &&
                accuracy <= 15f &&
                hasReliableReportedSpeed &&
                consecutiveMovingFixes >= 2 &&
                effectiveSpeed > stationaryThreshold

            val newMaxSpeed = if (isEligibleForMaxSpeed) {
                max(current.maxSpeedKmh, effectiveSpeed)
            } else {
                current.maxSpeedKmh
            }

            val newAvgSpeed = if (current.durationSeconds > 0 && newDistance > 0) {
                val hours = current.durationSeconds / 3600.0
                val km = newDistance / 1000.0
                (km / hours).toFloat()
            } else {
                0.0f
            }

            val newElevation = current.elevationGainMeters + elevationDelta
            val updatedPoints = if (isSearchingGps) {
                current.routePoints
            } else {
                current.routePoints.add(point)
            }

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
