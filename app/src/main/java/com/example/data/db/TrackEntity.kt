package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val totalDistanceMeters: Double,
    val durationSeconds: Long,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val elevationGainMeters: Double,
    val pointsCount: Int,
    val pointsJson: String
)
