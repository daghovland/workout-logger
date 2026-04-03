# Android App Setup Guide

This is a Kotlin + Jetpack Compose Android app that replicates the PWA and adds
Samsung Health integration.

## Prerequisites

1. **Android Studio** (Hedgehog 2023.1.1 or later) — download from developer.android.com
2. **Android SDK** — installed via Android Studio's SDK Manager
   - SDK Platform: Android 14 (API 34) and Android 10 (API 29)
   - Build Tools 34.0.0
3. **A Samsung phone** running One UI 6+ for Samsung Health features

---

## 1. First-time setup

```bash
cd src/

# Copy the secrets template
cp local.properties.template local.properties
# Edit local.properties and set:
#   sdk.dir=/home/dag/Android/Sdk   (or wherever your SDK is)
#   SUPABASE_URL=...
#   SUPABASE_ANON_KEY=...
```

The Supabase values are already in the template (same as the PWA).

---

## 2. Open in Android Studio

File → Open → select the `src/` folder. Android Studio will detect the
Gradle project automatically and sync dependencies (~2 min first time).

---

## 3. Run the app

Connect your Samsung phone via USB, enable **Developer Options → USB Debugging**,
then click the green ▶ Run button (or Shift+F10).

Alternatively, use an AVD (Android Virtual Device) — though Samsung Health
only works on real hardware.

---

## 4. Samsung Health SDK setup

Samsung Health SDK is **not on Maven Central** — you must download it manually.

### Step 1: Register as a Samsung Health developer
Go to https://developer.samsung.com/health/android/overview.html
and create a developer account. This is free.

### Step 2: Download the SDK
From the portal download **"Samsung Health Data 1.5.0"** (the classic SDK, not
the newer "Samsung Health Platform"). You get a zip containing:
- `samsung-health-data-1.5.0.aar`
- Sample code and documentation

### Step 3: Add the AAR to the project
```bash
mkdir -p src/app/libs
cp ~/Downloads/samsung-health-data-1.5.0.aar src/app/libs/
```

### Step 4: Enable it in Gradle
In `app/build.gradle.kts`, uncomment:
```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

### Step 5: Get a partner key
In the Samsung Health developer portal, register your app with:
- Package name: `no.daglifts.workout`
- SHA-256 fingerprint of your debug keystore (run: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`)

You'll receive a partner key string. Add it to `app/src/main/res/values/strings.xml`:
```xml
<string name="samsung_health_partner_key">YOUR_KEY_HERE</string>
```

### Step 6: Uncomment the SDK code
In `SamsungHealthRepository.kt`, follow the `UNCOMMENT` instructions.
Each query function has the real SDK code commented out beneath the stub.

### Step 7: Declare the service in the manifest
In `AndroidManifest.xml`, uncomment the `<service>` block for
`com.samsung.android.sdk.healthdata.HealthDataService`.

### Step 8: Request permissions at runtime
In `MainActivity.kt` (or wherever you add a "Connect Samsung Health" button),
call `vm.healthRepo.requestPermissions(this)`. The Samsung Health permission
dialog will appear asking the user to grant access to steps, sleep, heart rate, etc.

---

## 5. Google OAuth (Supabase sign-in)

The PWA uses Supabase Google OAuth. The Android app uses the same.

You need to add the Android OAuth redirect URI to your Supabase project:

1. Go to your Supabase dashboard → Authentication → URL Configuration
2. Under **Redirect URLs**, add: `no.daglifts.workout://login-callback`
3. Also add your app's SHA-256 fingerprint to your Google Cloud OAuth 2.0 client
   (in Google Cloud Console → APIs & Services → Credentials → your OAuth client → Android)

The deep link `no.daglifts.workout://login-callback` is already declared in
`AndroidManifest.xml` and handled in `MainActivity.onNewIntent()`.

---

## Architecture overview (for a backend developer)

| Android concept        | Backend analogy                          |
|------------------------|------------------------------------------|
| `ViewModel`            | Service layer / controller state         |
| `StateFlow`            | Reactive stream (like Reactor Flux/Mono) |
| `collectAsState()`     | Subscribe to stream in UI                |
| `Room` (SQLite)        | JPA/Hibernate for local SQLite DB        |
| `Compose`              | Declarative UI (like JSX / SwiftUI)      |
| `LaunchedEffect`       | Side-effect on state change (useEffect)  |
| `WorkoutApp`           | Spring ApplicationContext / DI container |
| `Coroutines`           | async/await (structured concurrency)     |
| `Flow`                 | Kotlin reactive streams                  |

### Data flow
```
Samsung Health SDK ──┐
                     ├──▶ WorkoutViewModel ──▶ Compose UI
Room (SQLite) ───────┤         │
                     │         ▼
Supabase ────────────┘   SupabaseRepository (sync)
```

### Key files
- `ExerciseDefinitions.kt` — exercise data (ported from PWA's JS constants)
- `WorkoutViewModel.kt`    — all business logic and UI state
- `WorkoutRepository.kt`   — Room database operations
- `SupabaseRepository.kt`  — Supabase sync + AI edge functions
- `SamsungHealthRepository.kt` — Samsung Health SDK wrapper
- `SessionScreen.kt`       — active workout UI
- `HomeScreen.kt`          — home screen with session type selection

---

## 6. Data compatibility with the PWA

Sessions saved in the Android app sync to the same Supabase tables as the PWA.
The local Room database uses the same JSON schema as the PWA's IndexedDB, so
exported JSON files are interoperable between both apps.

---

## 7. Building a release APK

```bash
cd src/
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release-unsigned.apk
```

You'll need to sign it before installing outside of Android Studio.
For development, debug builds install directly via Android Studio.
