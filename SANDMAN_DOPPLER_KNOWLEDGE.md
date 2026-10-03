# Sandman Doppler Knowledge Center

## Cloud Provisioning Flow (Working Path)

1. **Login**: `POST https://api.sandmandoppler.bycopilot.com/v4/auth/login`
   - Body: `{ "authenticationDetails": { "applicationId": "SANDMANDOPPLER", "email": "...", "password": "..." }, "deviceDetails": { "applicationVersion": "154", "deviceId": "...", "deviceModel": "...", "deviceType": "PHONE", "osType": "ANDROID", "osVersion": "...", "timezone": { ... } } }`
   - Response: `{ "accessToken": "...", "expiresIn": n, "refreshToken": "..." }`

2. **Fetch things**: `GET https://api.sandmandoppler.bycopilot.com/v4/things` with `Authorization: Bearer <accessToken>`
   - Response shape: `{ "things": [ { "id": "...", "info": { "physicalId": "Doppler-...", "name": "...", "model": "...", "firmware": "..." }, "status": { "lastSeen": ... } } ] }`
   - **Critical**: DSN comes from `info.physicalId` (NOT `id`). Firmware/model under `info.`.

3. **Fetch local key**: `GET https://control.sandmandoppler.com/{dsn}/localkey` with `Authorization: Bearer <accessToken>`
   - Response: `{ "localkey": "...", "ipAddie": "...", "port": n }` — **port may not be 5443**; default to 5443 only if absent.
   - **408 means the device didn't answer in time** — retry once before surfacing failure.

4. **Persist**: Store `accessToken`, `refreshToken`, `dsn`, `localKey`, `ipAddress`, `port` in `TokenStore` (AES-256-GCM encrypted SharedPreferences).

## Cloud vs LAN Control

- **LAN (local REST on port 5443)**: The clock runs an oatpp HTTPS daemon. Requests go to `http(s)://<ip>:<port>/{dsn}/...` with Bearer token `<nonce>|<base64(SHA256(nonce+localKey))>`.
- **Cloud fallback**: When the LAN daemon is unreachable (408 on `/localkey`, connection refused, or port scan shows no 5443), switch to `DopplerCloudApi` which mirrors every endpoint against `https://control.sandmandoppler.com/{dsn}/...` using the cloud `accessToken`. This works across network boundaries (your phone on 5G, clock on 2.4G repeater).

## Doppyler Package Findings

Downloaded from PyPI `doppyler==0.0.20` and read its source (`client.py`, `const.py`, `model/doppler.py`). Key discoveries:

- **Base URLs**: `BASE_SANDMAN_API_URL = "https://control.sandmandoppler.com"`, `BASE_COPILOT_API_URL = "https://api.sandmandoppler.bycopilot.com/v4"`
- **Auth**: `LOGIN_URL = f"{BASE_AUTH_URL}/login"`, `THINGS_URL = f"{BASE_COPILOT_API_URL}/things"`, `LOCALKEY_URL = f"{BASE_SANDMAN_API_URL}/{dsn}/localkey"`
- **Cloud payloads**: `GET /v4/things` returns `{ "things": [ { "id": "...", "info": { "name", "firmware", "physicalId", "model" }, "status": ... } ] }`
- **Local key response**: `{ "localkey": "...", "ipAddie": "...", "port": int }` — note `localkey` is **all lowercase**.
- **Doppler model**: stores `dsn = device_info["serialNum"]`, `local_key = local_info["localkey"]`, `ip_address = local_info["ipAddie"]`, `port = local_info["port"]`; local path = `https://{ip}:{port}`.
- **No Bluetooth control profile**: the official SDK has zero Bluetooth code; BT is A2DP audio sink only.

## API Endpoint Summary (from Postman collection)

All hardware commands go to `https://control.sandmandoppler.com/{dsn}/...`:

| Path | Method | Purpose |
|------|--------|---------|
| `/device` | GET | Device info |
| `/hardware/volume` | GET/PUT | Volume 0-100 |
| `/hardware/light-sensor` | GET | Lux reading |
| `/hardware/day-mode` | GET | Day/night mode |
| `/software/time-mode` | GET/PUT | 12/24h |
| `/doptime/utc-time` | GET | Current UTC hour/min |
| `/hardware/high-display-color` | GET/PUT | Day color RGB |
| `/hardware/low-display-color` | GET/PUT | Night color RGB |
| `/hardware/high-display-brightness` | GET/PUT | Day brightness 0-100 |
| `/hardware/low-display-brightness` | GET/PUT | Night brightness 0-100 |
| `/hardware/high-button-color` | GET/PUT | Day button color |
| `/hardware/low-button-color` | GET/PUT | Night button color |
| `/hardware/high-button-brightness` | GET/PUT | Day button brightness |
| `/hardware/low-button-brightness` | GET/PUT | Night button brightness |
| `/alarms` | GET/PUT/DELETE | Full CRUD |
| `/alarms/sounds` | GET | 20 sound filenames |
| `/alarms/sounds/play` | POST | Preview sound |
| `/software/weather` | GET/PUT | Weather config |
| `/software/weather-wakeup-time` | GET/PUT | Weather alarm lead time |
| `/alexa/lwa-status` | GET | Login-with-Amazon status |
| `/alexa/tap-talk-tone` | GET/PUT | Tap tone enable |
| `/alexa/wake-word-tone` | GET/PUT | Wake word tone enable |
| `/hardware/display-text` | PUT | Scrolling text |
| `/hardware/small-display-digits` | PUT | Mini-display number |
| `/hardware/display-dots` | PUT | 29-LED lightbar animation |

## Transport Invariants (enforced by tests)

- **Serialized requests.** The Doppler's oatpp daemon is single-threaded, so *every* request must be serialized through `DopplerLocalApi.requestGate`. This applies to the cloud path too: `DopplerCloudApi` overrides `executeAuthenticatedRequest` wholesale, so it must take the same gate or cloud mode silently runs requests concurrently. See `RequestSerializationTest`.
- **One URL seam.** `DopplerCloudApi.buildUrl(path)` is the only place a control-plane URL is built. Override it to redirect cloud traffic at a test double; never inline a host literal.
- The gate is released on every exit path, including thrown exceptions, so a failed request cannot poison it.

### RequestGate priority — read this before adding an endpoint

`RequestGate` is a *priority* gate, not a bare `Mutex`, and the distinction matters:

- `DopplerRepository.refresh()` issues **22 sequential GETs**. Under a plain fair mutex, a user action issued mid-sweep queues behind every remaining poll request. Over `control.sandmandoppler.com` that produced a measured **10–20 s delay** before a volume change was even sent. That is the reason this gate exists — do not "simplify" it back to a `Mutex`.
- `INTERACTIVE` = user-initiated (slider commits, toggles, alarm edits, probes). Served ahead of queued polls.
- `BACKGROUND` = the periodic poll. Yields to anything interactive.
- **All 22 poll getters must go through `executePollRequest(path)`**, never `executeAuthenticatedRequest("GET", ...)`. Centralizing it is deliberate: tagging getters by hand is how one gets missed, and a single stray interactive read reintroduces the lag. `RequestSerializationTest` pins this.
- The one exception is `executeConfirmRequest(path)` — the post-write read-back used to confirm a value actually landed (e.g. firmware clamping brightness). It is interactive because it exists to reflect a user action immediately.
- New endpoints: writes default to `INTERACTIVE` (the default parameter), so only new *getters* need attention.

### Cost of serialization

Serializing is not free: a poll cycle is now the sum of 22 round-trips. On a slow link that can exceed the poll interval, so the poll is effectively always running. Priority ordering keeps the user responsive, but if latency stays high, the fix is to trim the poll (fewer endpoints, adaptive interval, or skip reads while the user is actively dragging) — not to weaken the gate.

## Verdict Taxonomy (for probeOverrideSupport)

- `ACCEPTED_UNVERIFIED` — clock/relay answered 2xx. **This is the expected result for the two display overrides and it does not prove anything rendered.** `hardware/display-text` and `hardware/small-display-digits` are one-shot display commands with no GET counterpart, so there is nothing to read back, and the cloud relay returns 2xx for requests the clock never acts on. Only the clock face can confirm.
- `SUPPORTED` — reserved for overrides that expose a GET, where the stored value was read back and matched. The two display overrides cannot reach this verdict.
- `REJECTED` — clock routed the request but refused the payload (400/422); fix is on payload side. **Do not report this as a firmware gap** — the route exists, so it is never a missing-implementation problem.
- `NOT_SUPPORTED` — clock has no route for the endpoint at all (404/405/501); this *is* a firmware gap
- `UNKNOWN` — unhandled HTTP status (500, 503, etc.)

### Why a 2xx is not proof

A `PUT` to `hardware/display-text` returning 2xx tells you the *route exists*. It does not tell you the firmware implements the override, nor that the cloud relay forwarded it. Observed in practice: probe returned 2xx, no text appeared. Treat `ACCEPTED_UNVERIFIED` as "worth trying", not "works".

---

*Compiled from doppyler==0.0.20 source reverse‑engineering, the official Postman collection (portals.docsie.io), and live traffic against `control.sandmandoppler.com`. Intended as a knowledge center for future AI agents working on this project.*