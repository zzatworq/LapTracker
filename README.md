# Lap Tracker

Android app that records your GPS route and distance while location is on, and marks laps:

- **Manually** – the Lap button in the app, or the **Lap** action in the notification (works with the screen off)
- **On phone calls** – a lap when a call starts and/or when it ends (each can be switched off in the app)
- A final **Finish** lap is added when you press Stop

Each lap stores its distance, duration, running total and the position where it was marked.
Recordings are kept in **History**, where you can view laps, export a **GPX** track (with lap waypoints), export a **laps CSV**, or delete a recording. Exports go to `Downloads/LapTracker`.

If location is switched off mid-recording the app pauses and does not count distance across the gap; it resumes when location comes back.

No third-party libraries, no internet permission, all data stays on the phone. Requires Android 10+.

## Get the APK

### Option A – GitHub Actions (no tools needed)
1. Create a GitHub repo and push this folder to it (branch `main`).
2. Open the repo's **Actions** tab → **Build APK** → wait for it to finish (a few minutes).
3. Download the `lap-tracker-apk` artifact, unzip it, and you have `app-debug.apk`.

### Option B – Android Studio
Open this folder in Android Studio, let it sync (if it asks for a Gradle version, pick 8.9 or newer), then **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Option C – command line
With JDK 17, the Android SDK (platform 34) and Gradle 8.9 installed: `gradle assembleDebug`.

## Install
Copy the APK to the phone, open it, and allow "Install unknown apps" for your file manager/browser when asked.

## First run
Grant **precise location**, **notifications** and **phone** (call state) when asked. Choose **Allow all the time / While using** for location – recording continues in the background through a notification, so "while using" is enough.
Denying the phone permission only disables call laps.

## For reliable background tracking
Some phone brands kill background apps aggressively. In Settings → Apps → Lap Tracker → Battery, choose **Unrestricted** (or "Don't optimize").

## Tuning
Constants at the top of `TrackingService.kt`: minimum accuracy (35 m), jitter threshold (3 m), jump filter and gap length.
