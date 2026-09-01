package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.ui.components.ControlBar
import com.example.ui.components.GpsHeaderBar
import com.example.ui.components.HistoryBottomSheet
import com.example.ui.components.MetricStatCard
import com.example.ui.components.ResetConfirmDialog
import com.example.ui.components.SaveTrackDialog
import com.example.ui.components.SpeedGauge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldAccent

fun checkHasLocationPermission(context: Context): Boolean {
    val fineLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineLocation || coarseLocation
}

fun checkHasFineLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val trackingState by viewModel.trackingState.collectAsState()
    val savedTracks by viewModel.savedTracks.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()

    val showSaveDialog by viewModel.showSaveDialog.collectAsState()
    val showResetDialog by viewModel.showResetDialog.collectAsState()
    val showHistorySheet by viewModel.showHistorySheet.collectAsState()

    var hasLocationPerm by remember { mutableStateOf(checkHasLocationPermission(context)) }
    var hasFineLocationPerm by remember { mutableStateOf(checkHasFineLocationPermission(context)) }
    var permissionRequestInFlight by remember { mutableStateOf(false) }
    var hasAutoOpenedSettingsThisSession by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (!permissionRequestInFlight) return@rememberLauncherForActivityResult
        permissionRequestInFlight = false

        hasLocationPerm = checkHasLocationPermission(context)
        hasFineLocationPerm = checkHasFineLocationPermission(context)
        if (hasFineLocationPerm) {
            hasAutoOpenedSettingsThisSession = false
            viewModel.startPassiveGpsUpdates()
            return@rememberLauncherForActivityResult
        }

        if (activity != null && !hasAutoOpenedSettingsThisSession) {
            val showFineRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            val showCoarseRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            val permissionDialogBlocked = !showFineRationale && !showCoarseRationale
            if (permissionDialogBlocked) {
                hasAutoOpenedSettingsThisSession = true
                openAppDetailsSettings(context)
            }
        }
    }

    fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        try {
            permissionRequestInFlight = true
            permissionLauncher.launch(perms)
        } catch (e: Exception) {
            permissionRequestInFlight = false
            // Fallback to app details settings if direct prompt fails
            try {
                openAppDetailsSettings(context)
            } catch (_: Exception) {}
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasLocationPerm = checkHasLocationPermission(context)
        hasFineLocationPerm = checkHasFineLocationPermission(context)
        if (hasFineLocationPerm) viewModel.startPassiveGpsUpdates()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!hasFineLocationPerm) {
            requestPermissions()
        } else {
            viewModel.startPassiveGpsUpdates()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.stopPassiveGpsUpdates()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("main_screen_scaffold"),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Direct Dashboard Access - Never blocks the user!
            SingleScreenDashboard(
                trackingState = trackingState,
                hasLocationPermission = hasLocationPerm,
                onRequestPermissions = { requestPermissions() },
                isDarkTheme = isDarkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
                onStart = {
                    if (!hasFineLocationPerm) {
                        requestPermissions()
                    } else {
                        viewModel.startTracking()
                    }
                },
                onPause = { viewModel.pauseTracking() },
                onResume = { viewModel.resumeTracking() },
                onStop = { viewModel.stopTracking() },
                onReset = { viewModel.requestReset() },
                onSave = { viewModel.openSaveDialog() },
                onOpenHistory = { viewModel.openHistorySheet() }
            )

            // Dialogs & Sheets
            if (showSaveDialog) {
                SaveTrackDialog(
                    distanceText = trackingState.formattedDistance,
                    durationText = trackingState.formattedDuration,
                    avgSpeedText = String.format("%.1f km/h", trackingState.avgSpeedKmh),
                    onConfirm = { title -> viewModel.saveCurrentTrack(title) },
                    onDismiss = { viewModel.dismissSaveDialog() }
                )
            }

            if (showResetDialog) {
                ResetConfirmDialog(
                    onConfirm = { viewModel.confirmReset() },
                    onDismiss = { viewModel.dismissResetDialog() }
                )
            }

            if (showHistorySheet) {
                HistoryBottomSheet(
                    tracks = savedTracks,
                    onDismiss = { viewModel.dismissHistorySheet() },
                    onDeleteTrack = { track -> viewModel.deleteTrack(track) }
                )
            }
        }
    }
}

@Composable
fun SingleScreenDashboard(
    trackingState: com.example.data.models.TrackingState,
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    isDarkTheme: Boolean?,
    onToggleTheme: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top GPS Status & Header
        GpsHeaderBar(
            signalQuality = trackingState.signalQuality,
            accuracyMeters = trackingState.gpsAccuracyMeters,
            hasLocationPermission = hasLocationPermission,
            onRequestPermission = onRequestPermissions,
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme
        )

        // Hero Speedometer Arc Gauge & Big Digital Value
        SpeedGauge(
            currentSpeedKmh = trackingState.currentSpeedKmh,
            maxSpeedKmh = trackingState.maxSpeedKmh,
            status = trackingState.status
        )

        // Main Metric Cards Grid (2x2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricStatCard(
                title = "Średnia Prędkość",
                value = String.format("%.1f", trackingState.avgSpeedKmh),
                unit = "km/h",
                icon = Icons.Default.Speed,
                accentColor = EmeraldAccent,
                modifier = Modifier.weight(1f),
                testTag = "card_avg_speed"
            )

            MetricStatCard(
                title = "Dystans",
                value = trackingState.formattedDistanceValue,
                unit = trackingState.distanceUnit,
                icon = Icons.Default.Navigation,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                testTag = "card_distance"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricStatCard(
                title = "Czas Aktywności",
                value = trackingState.formattedDuration,
                unit = "",
                icon = Icons.Default.Timer,
                accentColor = AmberWarning,
                modifier = Modifier.weight(1f),
                testTag = "card_duration"
            )

            MetricStatCard(
                title = "Maks. Prędkość",
                value = String.format("%.1f", trackingState.maxSpeedKmh),
                unit = "km/h",
                icon = Icons.Default.ElectricBolt,
                accentColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
                testTag = "card_max_speed"
            )
        }

        // Secondary Telemetry Bar (Pace, Altitude, Elevation Gain)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("telemetry_bar_surface"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pace
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "TEMPO",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = trackingState.formattedPace,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Altitude
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "WYSOKOŚĆ",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format("%.0f m", trackingState.altitudeMeters),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Elevation Gain
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "PRZEWYŻSZ.",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format("+%.0f m", trackingState.elevationGainMeters),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // Bottom Action Controls Bar
        ControlBar(
            status = trackingState.status,
            hasRecordedData = trackingState.routePoints.isNotEmpty() || trackingState.distanceMeters > 0,
            hasLocationPermission = hasLocationPermission,
            onStart = onStart,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
            onReset = onReset,
            onSave = onSave,
            onOpenHistory = onOpenHistory
        )

        Spacer(modifier = Modifier.height(4.dp))
    }
}
