package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.GpsTrackerApp
import com.example.data.db.TrackEntity
import com.example.data.models.TrackingState
import com.example.location.GpsTrackingManager
import com.example.service.TrackingService
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as GpsTrackerApp
    private val gpsManager: GpsTrackingManager = app.gpsManager
    private val trackDao = app.database.trackDao()

    val trackingState: StateFlow<TrackingState> = gpsManager.trackingState

    val savedTracks: StateFlow<List<TrackEntity>> = trackDao.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isDarkTheme = MutableStateFlow<Boolean?>(null) // null = system default
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()

    private val _showSaveDialog = MutableStateFlow(false)
    val showSaveDialog: StateFlow<Boolean> = _showSaveDialog.asStateFlow()

    private val _showResetDialog = MutableStateFlow(false)
    val showResetDialog: StateFlow<Boolean> = _showResetDialog.asStateFlow()

    private val _showHistorySheet = MutableStateFlow(false)
    val showHistorySheet: StateFlow<Boolean> = _showHistorySheet.asStateFlow()

    private val moshi = Moshi.Builder().build()
    private val listType = Types.newParameterizedType(List::class.java, Array<Double>::class.java)
    private val adapter = moshi.adapter<List<Array<Double>>>(listType)

    fun startTracking() {
        try {
            TrackingService.startService(getApplication())
        } catch (_: Exception) {}
        gpsManager.startTracking()
    }

    fun pauseTracking() {
        try {
            TrackingService.pauseService(getApplication())
        } catch (_: Exception) {}
        gpsManager.pauseTracking()
    }

    fun resumeTracking() {
        try {
            TrackingService.resumeService(getApplication())
        } catch (_: Exception) {}
        gpsManager.resumeTracking()
    }

    fun stopTracking() {
        try {
            TrackingService.stopService(getApplication())
        } catch (_: Exception) {}
        gpsManager.stopTracking()
    }

    fun requestReset() {
        if (trackingState.value.distanceMeters > 50 || trackingState.value.durationSeconds > 10) {
            _showResetDialog.value = true
        } else {
            confirmReset()
        }
    }

    fun confirmReset() {
        _showResetDialog.value = false
        stopTracking()
        gpsManager.resetCounters()
    }

    fun dismissResetDialog() {
        _showResetDialog.value = false
    }

    fun openSaveDialog() {
        _showSaveDialog.value = true
    }

    fun dismissSaveDialog() {
        _showSaveDialog.value = false
    }

    fun openHistorySheet() {
        _showHistorySheet.value = true
    }

    fun dismissHistorySheet() {
        _showHistorySheet.value = false
    }

    fun toggleTheme() {
        _isDarkTheme.update { current ->
            when (current) {
                null -> true
                true -> false
                false -> null
            }
        }
    }

    fun saveCurrentTrack(customTitle: String? = null) {
        val state = trackingState.value
        val points = state.routePoints
        if (points.isEmpty() && state.distanceMeters <= 0) {
            _showSaveDialog.value = false
            return
        }

        viewModelScope.launch {
            val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
            val title = customTitle?.takeIf { it.isNotBlank() } ?: "Trasa $dateStr"
            val startTime = points.firstOrNull()?.timestamp ?: (System.currentTimeMillis() - state.durationSeconds * 1000)
            val endTime = System.currentTimeMillis()

            // Serialize points as lat/lng/speed arrays to keep DB compact
            val pointsData = points.map { arrayOf(it.latitude, it.longitude, it.speedKmh.toDouble(), it.altitude) }
            val pointsJson = try {
                adapter.toJson(pointsData)
            } catch (e: Exception) {
                "[]"
            }

            val entity = TrackEntity(
                title = title,
                startTime = startTime,
                endTime = endTime,
                totalDistanceMeters = state.distanceMeters,
                durationSeconds = state.durationSeconds,
                avgSpeedKmh = state.avgSpeedKmh,
                maxSpeedKmh = state.maxSpeedKmh,
                elevationGainMeters = state.elevationGainMeters,
                pointsCount = points.size,
                pointsJson = pointsJson
            )

            trackDao.insertTrack(entity)
            _showSaveDialog.value = false
            confirmReset()
        }
    }

    fun deleteTrack(track: TrackEntity) {
        viewModelScope.launch {
            trackDao.deleteTrack(track)
        }
    }
}
