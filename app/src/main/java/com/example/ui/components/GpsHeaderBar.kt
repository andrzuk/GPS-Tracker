package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.GpsSignalQuality
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BentoHeroLilac
import com.example.ui.theme.BentoHeroOnLilac
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.RoseDestructive

@Composable
fun GpsHeaderBar(
    signalQuality: GpsSignalQuality,
    accuracyMeters: Float,
    hasLocationPermission: Boolean = true,
    onRequestPermission: () -> Unit = {},
    isDarkTheme: Boolean?,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("gps_header_bar"),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Branding & Logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = if (!hasLocationPermission) {
                    Modifier.clickable { onRequestPermission() }
                } else Modifier
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BentoHeroLilac),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = BentoHeroOnLilac,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = "GPS TRACKER",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    // GPS Accuracy Indicator
                    val (signalText, signalColor) = when {
                        !hasLocationPermission -> "Brak uprawnień GPS (kliknij)" to AmberWarning
                        signalQuality == GpsSignalQuality.EXCELLENT -> "GPS: Doskonały (±${accuracyMeters.toInt()}m)" to EmeraldAccent
                        signalQuality == GpsSignalQuality.GOOD -> "GPS: Dobry (±${accuracyMeters.toInt()}m)" to EmeraldAccent
                        signalQuality == GpsSignalQuality.WEAK -> "GPS: Słaby (±${accuracyMeters.toInt()}m)" to AmberWarning
                        else -> "Szukanie sygnału GPS..." to RoseDestructive
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(signalColor)
                        )
                        Text(
                            text = signalText,
                            style = MaterialTheme.typography.labelSmall,
                            color = signalColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Quick Actions: Theme Toggle
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("btn_toggle_theme")
            ) {
                Icon(
                    imageVector = if (isDarkTheme == false) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = "Zmień motyw jasny/ciemny",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

