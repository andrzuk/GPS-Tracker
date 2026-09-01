# GPS Tracker

GPS Tracker is an Android app built with Kotlin and Jetpack Compose for real-time GPS activity tracking.

It tracks:
- Current speed
- Average speed
- Max speed
- Distance
- Duration
- Altitude
- Elevation gain

The app runs tracking in a foreground service, shows live status in a notification, and lets you save completed tracks locally.

## Features

- Real-time GPS tracking with fused location provider
- Start, pause, resume, stop tracking flow
- Foreground service with persistent notification and quick actions
- Live dashboard with speed gauge and metric cards
- GPS signal quality and accuracy display
- Save track sessions to local Room database
- Track history bottom sheet with delete support
- Runtime permission handling for location and notifications
- Light/dark/system theme toggle
- Unit, Robolectric, and Compose screenshot tests

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- AndroidX Lifecycle + ViewModel
- Coroutines + Flow
- Google Play Services Location
- Room (SQLite)
- Moshi
- Retrofit + OkHttp
- Firebase BoM dependencies (AI/App Check enabled in build)
- Accompanist Permissions
- Robolectric + Roborazzi

## Project Structure

```text
app/src/main/java/com/example/
  MainActivity.kt
  GpsTrackerApp.kt
  data/
    db/
    models/
  location/
    GpsTrackingManager.kt
  service/
    TrackingService.kt
  ui/
    MainScreen.kt
    MainViewModel.kt
    components/
```

## Requirements

- Android Studio (latest stable recommended)
- Android SDK:
  - minSdk: 24
  - targetSdk: 36
  - compileSdk: 36
- JDK 17 or newer
- Internet access for dependency download

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/andrzuk/GPS-Tracker.git
cd GPS-Tracker
```

### 2. Configure environment values

Copy the template and set values if needed:

```bash
cp .env.example .env
```

Windows PowerShell alternative:

```powershell
Copy-Item .env.example .env
```

Notes:
- The project is configured to read secrets from .env.
- .env.example includes GEMINI_API_KEY as a commented template.
- If your app flow needs Gemini calls, uncomment and set GEMINI_API_KEY.

### 3. Optional: Firebase config

The build is configured to tolerate a missing google-services.json.
If you use Firebase services that require it, place google-services.json in app/.

### 4. Build the app

macOS/Linux:

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

### 5. Run on device/emulator

- Open the project in Android Studio
- Select a device or emulator
- Run the app module

## Runtime Permissions

The app requests:
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_LOCATION
- POST_NOTIFICATIONS (Android 13+)

## Tracking Flow

1. Grant location permission.
2. Tap Start.
3. App begins foreground GPS tracking.
4. Use Pause/Resume/Stop as needed.
5. Save the track to local history.
6. Review and delete entries in History.

## Data Model

Saved tracks are stored in Room table saved_tracks with fields like:
- title
- startTime, endTime
- totalDistanceMeters
- durationSeconds
- avgSpeedKmh, maxSpeedKmh
- elevationGainMeters
- pointsCount
- pointsJson (route points serialized as compact JSON)

## Testing

Run unit and Robolectric tests:

macOS/Linux:

```bash
./gradlew test
```

Windows:

```powershell
.\gradlew.bat test
```

Run instrumentation tests (device/emulator required):

macOS/Linux:

```bash
./gradlew connectedAndroidTest
```

Windows:

```powershell
.\gradlew.bat connectedAndroidTest
```

Screenshot tests use Roborazzi. Generated screenshots are stored under:
- app/src/test/screenshots/

## Release Signing

Release signing config supports environment-based values:
- KEYSTORE_PATH
- STORE_PASSWORD
- KEY_PASSWORD

If not provided, the build expects a keystore path fallback at project root.

## Notes

- Current package names include namespace com.example and applicationId com.aistudio.gpstracker.rtmmap.
- Some dependencies are intentionally commented in Gradle for optional future use.

## License

No license file is currently included. Add a LICENSE file if you plan to distribute this project.
