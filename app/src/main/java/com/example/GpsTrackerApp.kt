package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.location.GpsTrackingManager

class GpsTrackerApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var gpsManager: GpsTrackingManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        gpsManager = GpsTrackingManager.getInstance(this)
    }
}
