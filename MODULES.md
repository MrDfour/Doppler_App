# Sandman Doppler Android Architecture & Module Reference

This document provides a comprehensive map of all modules, contracts, networking layers, data models, and future expansion paths for automated agents and engineers working on the Sandman Doppler application.

---

## 1. High-Level Architecture Overview

```text
┌────────────────────────────────────────────────────────┐
│                   UI Layer (Compose)                   │
│  MainActivity  •  DashboardScreen  •  LightingScreen   │
│         AlarmsScreen  •  DiagnosticsScreen             │
└───────────────────────────▲────────────────────────────┘
                            │ StateFlow / Events
┌───────────────────────────┴────────────────────────────┐
│                    ViewModel Layer                     │
│                   DopplerViewModel                     │
│         Exposes reactive immutable UI state            │
└───────────────────────────▲────────────────────────────┘
                            │ Coroutine Dispatchers
┌───────────────────────────┴────────────────────────────┐
│                   Repository Layer                     │
│                  DopplerRepository                     │
│   Single source of truth, optimistic updates, polling  │
└─────────────▲────────────────────────────▲─────────────┘
              │                            │
┌─────────────┴─────────────┐ ┌────────────┴─────────────┐
│      Protocol Client      │ │     Security Layer       │
│      DopplerLocalApi      │ │       TokenStore         │
│  HTTP REST client, retries│ │  Android Keystore AES256 │
│  Mutex write serialization│ │ EncryptedSharedPreferences│
└─────────────▲─────────────┘ └──────────────────────────┘
              │
┌─────────────┴─────────────┐
│    Discovery Subsystem    │
│     DopplerDiscovery      │
│  mDNS (NSD) & Subnet Ping │
└───────────────────────────┘
```

---

## 2. Module & Directory Index

### 2.1 Android Native Module (`android/app/src/main/java/com/sandman/doppler/`)

| Package / File | Purpose & Responsibilities | Key Dependencies |
|---|---|---|
| **`MainActivity.kt`** | Root Android `ComponentActivity`. Initializes dependency tree (`TokenStore`, `DopplerLocalApi`, `DopplerRepository`, `DopplerViewModel`) and hosts the Material 3 `Scaffold` and `NavigationBar`. | Jetpack Compose, Material 3 |
| **`model/DopplerModels.kt`** | Strictly typed Kotlin data classes representing the Doppler device state, RGB colors, alarms, 29-LED lightbar effects, and auxiliary display overrides. | `kotlinx.serialization` |
| **`api/DopplerLocalApi.kt`** | Direct local HTTP transport. Connects to `http://<ip>:<port>`. Enforces connection/read timeouts, exponential backoff retries, request serialization via `Mutex`, and custom exception mapping. | OkHttp 4, Coroutines |
| **`api/DopplerDiscovery.kt`** | Local network discovery engine. Implements mDNS/DNS-SD via Android `NsdManager` and active Wi-Fi subnet scanning (`/24` CIDR probing) without requiring cloud accounts. | Android NSD, WifiManager, OkHttp |
| **`security/TokenStore.kt`** | Hardware-backed encrypted persistence. Protects credentials, session tokens, device DSNs, and local IP addresses using the Android Keystore with `AES256_GCM`. | `androidx.security:security-crypto` |
| **`repository/DopplerRepository.kt`** | Repository coordinating data flow. Emits `StateFlow<DopplerDeviceState?>`, manages automatic polling intervals (8–10s), handles optimistic UI mutations, and rolls back on transport errors. | Kotlin Coroutines, StateFlow |
| **`viewmodel/DopplerViewModel.kt`** | Android architecture component managing UI interaction, logging sanitized diagnostics, and delegating user commands to the repository. | AndroidX Lifecycle ViewModel |
| **`ui/theme/`** | Dark-mode design system with Doppler neon accent palettes (Cyan `#00DCFF`, Amber `#FF9600`, Deep Red `#FF2828`, Emerald `#10DC78`, Purple `#B43CFF`). | Compose Theme & Material 3 |
| **`ui/screens/`** | Screen composables: `DashboardScreen`, `DisplayLightingScreen`, `AlarmsScreen`, `DiagnosticsScreen`. | Jetpack Compose UI |

### 2.2 Companion Web & Local API Mock Server (`server.ts`, `src/`)

| File / Folder | Purpose |
|---|---|
| **`server.ts`** | Fullstack Node.js/Express server implementing the exact REST endpoints of the Sandman Doppler device. Provides in-memory Doppler clock state, smart button webhook simulation, alarm triggers, and CORS support for local development. |
| **`src/App.tsx`** | Interactive hardware simulation interface. Renders the simulated clock display, 29-LED lightbar animation canvas, physical button simulators, ambient light sensor slider, and live event log. |

---

## 3. Data Models & Protocol Contracts

### 3.1 `DopplerColor`
```kotlin
@Serializable
data class DopplerColor(val r: Int, val g: Int, val b: Int)
```
- Valid range: `0..255` per channel.
- Includes helpers: `toHex(): String` and `fromHex(hex: String): DopplerColor`.

### 3.2 `DopplerAlarm`
```kotlin
@Serializable
data class DopplerAlarm(
    val id: Int,              // 0 = System Alarm; 1..255 = User Alarms
    val name: String,
    val time: String,         // Format: "HH:MM" (24h string)
    val repeat: List<String>, // ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
    val color: DopplerColor,
    val volume: Int,          // 1..100
    val status: String,       // "set", "unarmed", "snoozed", "active"
    val sound: String         // e.g. "Sandman.mp3", "1980.mp3", "Rooster.mp3"
)
```

### 3.3 `LightBarEffect`
```kotlin
@Serializable
data class LightBarEffect(
    val mode: String,          // "set", "blink", "pulse", "comet", "sweep", "rainbow"
    val color: DopplerColor?,
    val colors: List<DopplerColor>?,
    val rainbow: Boolean = false,
    val speed: Int = 50,       // 0..255
    val duration: Int = 15,    // Duration in seconds
    val sparkle: String? = null, // "low", "medium", "high"
    val gap: Int? = null,      // Frame gap or dot gap
    val size: Int? = null,     // Dot block size
    val direction: String? = null // "left", "right", "bounce"
)
```

---

## 4. Local REST Endpoints Specification

All commands are issued to `http://<doppler-ip>:<port>`:

| Method | Endpoint | Description | Payload Format |
|---|---|---|---|
| `GET` | `/api/devices/:id` | Retrieve full hardware state (colors, brightness, alarms, sensors, volume). | N/A |
| `PATCH` | `/api/devices/:id` | Update configuration settings (e.g. brightness, volume, timezone, 24h mode). | JSON Patch (e.g. `{"masterVolume": 80}`) |
| `POST` | `/api/devices/:id/services/set_main_display_text` | Displays scrolling text across the front digits. | `{"text": "ALARM", "duration": 10, "speed": 50, "color": {...}}` |
| `POST` | `/api/devices/:id/services/set_mini_display_number` | Displays custom number on mini screen (-199 to 199). | `{"number": 99, "duration": 15, "color": {...}}` |
| `POST` | `/api/devices/:id/services/activate_light_bar_:mode` | Triggers a 29-LED lightbar effect. | `LightBarEffect` JSON |
| `POST` | `/api/devices/:id/services/stop_light_bar` | Turns off any active lightbar animation. | `{}` |
| `POST` | `/api/devices/:id/services/add_alarm` | Registers a new alarm. | `DopplerAlarm` JSON |
| `POST` | `/api/devices/:id/services/update_alarm` | Modifies an existing alarm. | `DopplerAlarm` JSON with target `id` |
| `POST` | `/api/devices/:id/services/delete_alarm` | Deletes an alarm. | `{"id": <alarm-id>}` |
| `POST` | `/api/devices/:id/button-press` | Simulates or dispatches a physical button press (`1`, `2`, `alarm`, `snooze`). | `{"button": "1"}` |

---

## 5. Security & Network Isolation Rules

1. **Local Cleartext Scope (`network_security_config.xml`)**:
   - Cleartext HTTP is restricted strictly to local RFC 1918 subnets (`192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) and `localhost`/`10.0.2.2`.
   - Global cleartext traffic is disabled (`cleartextTrafficPermitted="false"` on base config).
2. **Credential Sanitization**:
   - Diagnostic logs strip all tokens, authentication headers, and Wi-Fi pre-shared keys before rendering.
   - `TokenStore` persists sensitive records using Android Keystore `AES-256-GCM`.

---

## 6. Guidelines for Future Agentic Extensions

When adding new features or adapting to firmware updates:
- **Do not bypass `DopplerRepository`**: Composables should only observe `StateFlow` and dispatch events to `DopplerViewModel`.
- **Concurrency Safety**: Always wrap new multi-step commands in `DopplerLocalApi.requestMutex.withLock` to prevent concurrent write collisions on the Doppler's single-threaded HTTP daemon.
- **Protocol Unknowns**: If a response payload differs across firmware revisions, add a nullable field with `@SerialName` in `DopplerModels.kt` rather than modifying existing verified fields.
- **Unit Tests**: Add mock tests in `DopplerProtocolTest.kt` using `MockWebServer` for any newly introduced endpoints before modifying UI components.
