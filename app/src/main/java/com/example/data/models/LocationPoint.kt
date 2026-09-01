package com.example.data.models

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float = 0f,
    val altitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val accuracy: Float = 0f
)
