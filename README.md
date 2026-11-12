# Kxko Brief for Android

The Android app packages the Kxko Brief V2 interface locally instead of loading the old hosted dashboard.

## V2 interface

The web application from [Kxko4b/V2](https://github.com/Kxko4b/V2) is bundled in:

`app/src/main/assets/v2/`

The Android `MainActivity` serves it through AndroidX `WebViewAssetLoader` at the secure `appassets.androidplatform.net` origin. The main interface therefore ships with the APK and does not depend on GitHub Pages to load.

When syncing a newer V2 revision, copy the V2 static files into this assets directory. Keep the ride detail script named `ride-detail.js`, matching the filename referenced by V2's HTML and service worker.

## Native Android functionality

The existing Android project remains in place, including its cycling tracker/service, Study Hub, cycling quiz, Firebase messaging, device registration, update handling and notification listener.

## Supabase

The V2 frontend continues to use the configured Supabase project. The timetable Edge Function source is kept under `supabase/functions/untis-timetable/`; deploying backend changes remains a separate Supabase deployment step. Do not put service-role keys or other server secrets in the Android app.

## Build

Open the project in Android Studio or run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
