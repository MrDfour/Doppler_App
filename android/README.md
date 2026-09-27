# Sandman Doppler Standalone Native Android Application

A standalone, production-quality Android application for directly controlling personally owned Sandman Doppler smart clocks over local Wi-Fi, completely independent of Home Assistant or external cloud bridges.

## Features & Supported Capabilities

1. **Direct Local Network Communication (Zero Home Assistant Dependency)**
   - Communicates over Wi-Fi directly to the Doppler clock's local IP.
   - Requires no Home Assistant server, Raspberry Pi, Python daemon, or cloud relay.
   - Sub-second local response time for volume, display, and lighting commands.

2. **Full Display & 29-LED Lightbar Controls**
   - Day/Night display and button colors & brightness.
   - Dynamic transitions triggered by ambient lux sensor.
   - Real-time animated lightbar effects: `set`, `blink`, `pulse`, `comet`, `sweep`, and `rainbow`.
   - Text scrolling and mini-display number overrides.

3. **Complete Alarm Management**
   - Alarm 0 (System Alarm) and user-defined alarms.
   - Schedule repeat days, choose sound files (20 authentic Doppler tones), volume, and color.
   - Snooze and disarm support.

4. **Security & Cryptography**
   - Credentials, IP, and tokens stored securely via `EncryptedSharedPreferences` backed by the **Android Keystore (AES-256-GCM)**.
   - Strictly scoped cleartext traffic permitted only on private local subnets (`192.168.x.x`, `10.x.x.x`). TLS enforced everywhere else.

5. **Modern Reactive Architecture**
   - 100% Kotlin with Jetpack Compose & Material 3.
   - MVVM with `StateFlow` and Coroutines for background execution and UI updates.
   - OkHttp client with exponential backoff retry and request serialization to prevent race conditions.

## Project Structure

```
android/
├── build.gradle.kts                   # Root build configuration
├── settings.gradle.kts                # Project module declarations
├── gradle/libs.versions.toml          # Centralized dependency catalog
└── app/
    ├── build.gradle.kts               # Android application configuration
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml    # App manifest with local Wi-Fi permissions
        │   ├── res/xml/network_security_config.xml # Narrow local cleartext policy
        │   └── java/com/sandman/doppler/
        │       ├── MainActivity.kt    # Root Compose Activity
        │       ├── model/             # Typed data classes (DopplerColor, DopplerAlarm, etc.)
        │       ├── api/               # DopplerLocalApi HTTP client & error hierarchy
        │       ├── repository/        # DopplerRepository with optimistic updates
        │       ├── viewmodel/         # DopplerViewModel & StateFlows
        │       ├── security/          # Keystore TokenStore
        │       └── ui/                # Theme and Compose screens
        └── test/                      # Unit & MockWebServer protocol tests
```

## How to Build & Run in Android Studio

1. Open Android Studio (Ladybug or newer).
2. Select **Open** and choose the `android/` directory.
3. Allow Gradle to sync dependencies.
4. Select an Android device or emulator running API 26 or higher (Android 8.0+).
5. Build and install via:
   ```bash
   ./gradlew assembleDebug
   ```
6. Run unit and protocol tests via:
   ```bash
   ./gradlew testDebugUnitTest
   ```
