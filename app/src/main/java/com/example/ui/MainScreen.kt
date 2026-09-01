package com.example.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ControlBar
import com.example.ui.components.GpsHeaderBar
import com.example.ui.components.HistoryBottomSheet
import com.example.ui.components.MetricStatCard
import com.example.ui.components.ResetConfirmDialog
import com.example.ui.components.SaveTrackDialog
import com.example.ui.components.SpeedGauge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BentoHeroLilac
import com.example.ui.theme.BentoHeroOnLilac
import com.example.ui.theme.BentoOnPrimaryButton
import com.example.ui.theme.BentoPrimaryButton
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.VioletAccent
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val trackingState by viewModel.trackingState.collectAsState()
    val savedTracks by viewModel.savedTracks.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()

    val showSaveDialog by viewModel.showSaveDialog.collectAsState()
    val showResetDialog by viewModel.showResetDialog.collectAsState()
    val showHistorySheet by viewModel.showHistorySheet.collectAsState()

    // Permission handling
    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionState = rememberMultiplePermissionsState(permissions = permissions)

    LaunchedEffect(Unit) {
        if (!permissionState.allPermissionsGranted) {
            permissionState.launchMultiplePermissionRequest()
        }
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
            if (!permissionState.allPermissionsGranted) {
                // Permission request view
                LocationPermissionPrompt(
                    onRequestPermissions = { permissionState.launchMultiplePermissionRequest() }
                )
            } else {
                // Single-screen Complete Dashboard Layout
                SingleScreenDashboard(
                    trackingState = trackingState,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { viewModel.toggleTheme() },
                    onStart = { viewModel.startTracking() },
                    onPause = { viewModel.pauseTracking() },
                    onResume = { viewModel.resumeTracking() },
                    onStop = { viewModel.stopTracking() },
                    onReset = { viewModel.requestReset() },
                    onSave = { viewModel.openSaveDialog() },
                    onOpenHistory = { viewModel.openHistorySheet() }
                )
            }

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

@Composable
fun LocationPermissionPrompt(
    onRequestPermissions: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_permission_prompt")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = BentoHeroLilac,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.LocationSearching,
                            contentDescription = null,
                            tint = BentoHeroOnLilac,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Text(
                    text = "Dostęp do Lokalizacji GPS",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Aplikacja wymaga uprawnień do lokalizacji, aby rejestrować parametry GPS: prędkość bieżącą, średnią, maksymalną, przebyty dystans, czas oraz wysokość.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("btn_grant_location_permission"),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimaryButton,
                        contentColor = BentoOnPrimaryButton
                    )
                ) {
                    Text(
                        text = "Zezwól na Dostęp do GPS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
