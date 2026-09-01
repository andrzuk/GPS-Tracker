package com.example.data.models

enum class TrackingStatus {
    STOPPED,
    TRACKING,
    PAUSED
}

enum class GpsSignalQuality {
    DISCONNECTED,
    SEARCHING,
    WEAK,
    GOOD,
    EXCELLENT
}

data class TrackingState(
    val status: TrackingStatus = TrackingStatus.STOPPED,
    val currentSpeedKmh: Float = 0.0f,
    val avgSpeedKmh: Float = 0.0f,
    val maxSpeedKmh: Float = 0.0f,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Long = 0L,
    val currentLocation: LocationPoint? = null,
    val routePoints: List<LocationPoint> = emptyList(),
    val gpsAccuracyMeters: Float = 0.0f,
    val signalQuality: GpsSignalQuality = GpsSignalQuality.SEARCHING,
    val altitudeMeters: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val lastUpdatedTimestamp: Long = 0L
) {
    val isTracking: Boolean get() = status == TrackingStatus.TRACKING
    val isPaused: Boolean get() = status == TrackingStatus.PAUSED
    val isStopped: Boolean get() = status == TrackingStatus.STOPPED

    val formattedDuration: String
        get() {
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedDistance: String
        get() {
            return if (distanceMeters < 1000) {
                String.format("%.0f m", distanceMeters)
            } else {
                String.format("%.2f km", distanceMeters / 1000.0)
            }
        }

    val formattedDistanceValue: String
        get() {
            return if (distanceMeters < 1000) {
                String.format("%.0f", distanceMeters)
            } else {
                String.format("%.2f", distanceMeters / 1000.0)
            }
        }

    val distanceUnit: String
        get() = if (distanceMeters < 1000) "m" else "km"

    val formattedPace: String
        get() {
            if (currentSpeedKmh <= 0.5f) return "--:--"
            val minutesPerKm = 60.0 / currentSpeedKmh
            val mins = minutesPerKm.toInt()
            val secs = ((minutesPerKm - mins) * 60).toInt()
            return String.format("%d'%02d\"", mins, secs)
        }
}
