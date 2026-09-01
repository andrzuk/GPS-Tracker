package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BentoHeroLilac
import com.example.ui.theme.BentoHeroOnLilac
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.RoseDestructive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SaveTrackDialog(
    distanceText: String,
    durationText: String,
    avgSpeedText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultTitle = remember {
        "Trasa " + SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date())
    }
    var title by remember { mutableStateOf(defaultTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_save_track"),
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = BentoHeroLilac
            )
        },
        title = {
            Text(
                text = "Zapisz Przebytą Trasę",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Dystans: $distanceText • Czas: $durationText • Śr. prędkość: $avgSpeedText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nazwa trasy") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_track_title"),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title.ifBlank { defaultTitle }) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BentoHeroLilac,
                    contentColor = BentoHeroOnLilac
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("btn_confirm_save_track")
            ) {
                Text("Zapisz", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_cancel_save_track")
            ) {
                Text("Anuluj")
            }
        }
    )
}

@Composable
fun ResetConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_reset_confirm"),
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = RoseDestructive
            )
        },
        title = {
            Text(
                text = "Zresetować Liczniki?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
        },
        text = {
            Text(
                text = "Czy na pewno chcesz wyzerować bieżący dystans, czas oraz wyczyścić narysowaną trasę?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RoseDestructive,
                    contentColor = BentoHeroOnLilac
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("btn_confirm_reset")
            ) {
                Text("Zresetuj", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_cancel_reset")
            ) {
                Text("Anuluj")
            }
        }
    )
}

