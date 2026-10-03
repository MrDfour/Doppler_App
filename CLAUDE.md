# Sandman Doppler App — Agent Technical Reference

Single source of truth for AI agents and contributors working in **Doppler_App**.

## 1. What this repo is

- **Primary product:** a standalone Android app (`android/`, Kotlin/Jetpack Compose) that controls Sandman Doppler smart clocks directly over the LAN — no Home Assistant, proxy, or cloud required day-to-day.
- **Reference/mock web server:** `server.ts` (Express + Vite) serving (a) the legacy web dashboard/mock API at `/api/*` and (b) an authentic-protocol LAN mock at `/:dsn/*` (nonce + Bearer SHA-256 token, port-5443 semantics).
- **Legacy HA integration:** `custom_components/sandman_doppler/` kept for reference.

## 2. Non-negotiable rules

1. New behavior requires a passing test in `android/app/src/test/`.
2. Bug fixes require a regression test.
3. Quality gate before reporting done:
   ```bash
   cd android && ./gradlew testDebugUnitTest && ./gradlew assembleDebug
   ./node_modules/.bin/tsc --noEmit   # when touching server.ts / src/
   ```
4. Preserve the standalone invariant and the serialized-request constraint (the clock's oatpp server is single-threaded; never issue concurrent requests).
5. Preserve line endings; no gratuitous reformatting or import reordering.
6. Never document new work under an already-tagged release version in `CHANGELOG.md` (if present); use the `## [Unreleased]` placeholder with semver sections (`Added`/`Changed`/`Fixed`/`Removed`).

## 3. Protocol essentials (see STANDALONE_IMPLEMENTATION_PLAN.md for the full blueprint)

- Cloud bootstrap one time: `POST /v4/auth/login` → `GET /v4/things` → `GET control.../localkey` → store `localKey`+`dsn` in `EncryptedSharedPreferences`.
- Per-session LAN auth: `GET https://<ip>:5443/<dsn>/nonce` → `Token = "<nonce>|<base64(SHA256(nonce+localKey))>"` → `Authorization: Bearer <Token>`.
- TLS: custom `LanTrustManager` accepting the clock's self-signed cert on RFC-1918 subnets.
- Endpoints are granular: `/:dsn/device`, `/:dsn/hardware/volume`, `/:dsn/alarms`, `/:dsn/hardware/high-display-color`, etc.

## 4. Repo layout

```
android/app/src/main/java/com/sandman/doppler/
├── api/          # DopplerLocalApi (OkHttp, Mutex-serialized), LanTrustManager, DopplerDiscovery, CopilotCloudAuthClient
├── model/        # DopplerModels, DopplerState
├── repository/   # DopplerRepository (StateFlow, optimistic mutations + rollback)
├── security/     # TokenStore (Keystore AES-256-GCM)
├── ui/screens/   # Dashboard, DisplayLighting, Alarms, LightBar, Settings, Diagnostics
└── viewmodel/    # DopplerViewModel
tests are JUnit in android/app/src/test/java/com/sandman/doppler/
```

## 5. Testing notes

- Inject a `StandardTestDispatcher(testScheduler)`-backed `CoroutineScope` into `DopplerRepository` in tests; use `advanceTimeBy`, never real sleeps/virtual-time mismatches.
- Mock implementations of `DopplerLocalApi` must override **every** getter in the refresh path or tests will make real network calls.
- The web LAN mock (`server.ts`) is the end-to-end target for hardware-free testing: `GET /Doppler-deadbeef/nonce`, then derive the token with the mock `localKey` (`test-local-key-deadbeef` / `test-local-key-c001cafe`).

## 6. Honesty & disclosure

State your model name, which parts passed automated tests, and which parts need on-device/hardware verification.
