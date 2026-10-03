# Sandman Doppler Knowledge Center

> **Start here if you are a fresh agent session:** jump to **Live Hardware Findings** (what
> the clock actually does — `POST /alarms` is create-only, `status` is 10, LAN is dead), then
> **Cloud Latency: The Real Root Cause** (timeouts, the refresh storm, and why measuring
> beats inferring), then **Read This First: Repo State** (what is verified vs. still
> unproven), then **Open Issues** (what is deliberately not fixed), then **The UI Write
> Path** (why sliders feel instant even when the clock has not responded). The numbered
> sections below are protocol reference.

---

## Live Hardware Findings (probed against DSN `Doppler-10caaebb`, Oct 2026)

Everything in this section was **observed on the real clock**, not inferred from source. It
overrides assumptions the codebase used to make. If a test or comment here contradicts the
code, the code is wrong.

**Unit:** Enter Sandman, firmware `Escapement`, software `0.1214 Bucky`, serial
`Doppler-10caaebb`, mfr `Palo Alto Innovation`.

### LAN is not usable on this unit

`192.168.11.107` answers ICMP (~37ms, TTL 64) but has **no open TCP port at all** — not 5443,
not 443, nothing in 1–10000 that was scanned. Cloud relay mode is the only working transport.

### `GET /{dsn}/localkey` returns HTTP 408, consistently

4/4 attempts, ~15s each. `CopilotCloudAuthClient.fetchLocalKey` already retries once on 408.

**Consequence:** the app can **never** learn a LAN IP from the cloud. `savedIpAddress` stays
blank forever, so LAN mode cannot be configured automatically at all. This is why the
onboarding screen used to display a hardcoded IP — it had nothing real to show (fixed in
`9059a9f`: it now says "LAN host not discovered" rather than inventing one).

### The cloud relay itself works fine for reads

`GET /device`, `/alarms`, `/hardware/volume`, `/hardware/high-display-color`,
`/hardware/low-display-color` all return 200 in ~750–1000ms. Only `localkey` is broken.

### `POST /alarms` is CREATE-ONLY — this is the alarm bug

| Call | Result |
|---|---|
| `POST /alarms` with `{"id":200,...}` | created **`id: 4`** — body id ignored |
| `POST /alarms` with `{"id":4,...}` | created a **new `id: 5`**, left 4 untouched |
| `PUT /alarms/4` with `{"volume":7}` | updated alarm 4 in place, no new alarm |
| `PUT /alarms` (no id) | 404 |

So the real protocol is:

- `POST /alarms` → create; **the clock assigns the id and ignores yours**
- `PUT /alarms/{id}` → update in place
- `DELETE /alarms/{id}` → delete

`POST /alarms` responds with the **entire alarm list**, new alarm inserted **first**, and the
list is **not sorted by id**.

**Consequence:** the app used `POST` for both create and update (`createOrUpdateAlarm`). Every
*edit* of an alarm created a duplicate. That is why the probed clock held two byte-identical
alarms, `id: 2` and `id: 3`, both at 13:15 with identical colour, volume and sound. Fixed by
splitting `createAlarm` / `updateAlarm` and routing on ids the clock has actually confirmed.

### Active alarms report `status: 10`, not `1`

```json
{"id":0,"name":"Doppler System Alarm","time_hr":10,"time_min":0,"repeat":"0",
 "color":{"red":255,"green":0,"blue":0},"volume":100,"status":10,"src":0,
 "sound":"Harp.mp3","next_trigger":-1}
```

Every alarm on the device reports `status: 10`. The app modelled only `1 = enabled,
0 = disabled` and computed `isEnabled = status == 1`, so **every real alarm rendered dimmed
with its toggle showing off**, and toggling one wrote `1` — a value the device was never
observed to send. The clock stores `status` verbatim on create (sent 0, got 0 back), so `0` is
reliably "off" and `10` is "active".

Also note `repeat: ""` (empty string) on one-shot alarms, and `next_trigger: -1`.

### Colour and volume read fine

`GET /hardware/high-display-color` → `{"color":[180,60,255]}`,
`/hardware/low-display-color` → same, `/hardware/volume` → `{"volume":92}`.

The colour endpoints are an **array** `[r,g,b]`, not an object — do not confuse with the
alarm `color` field, which is an object `{"red":..,"green":..,"blue":..}`.

### Endpoint Access Matrix (probed live, Oct 2026)

Every documented endpoint was called through the relay. **The documentation in
`STANDALONE_IMPLEMENTATION_PLAN.md` is wrong about six payload schemas** and silent about
which endpoints are read-only.

**READS — 30 of 36 work** (~600–1100ms each):

| Endpoint | Live payload | App model | Correct? |
|---|---|---|---|
| `device` | `{"mfgrName":…,"modelNum":…,"serialNum":…,"firmware":"Escapement","hardware":"Enter Sandman","software":"0.1214 Bucky"}` | matches | ok |
| `doptime/utc-time` | `{"hour":20,"min":37}` | matches | ok |
| `doptime/timezone` | `{"timezone":"Canada/Saskatchewan"}` (as shipped) | matches | ok — **now `America/Chihuahua`**, see below |
| `doptime/offset` | `{"offset":0}` | matches | ok |
| `software/time-mode` | `{"timeMode":12}` | matches | ok |
| `hardware/volume` | `{"volume":92}` | matches | ok |
| `hardware/sound-preset` | `{"preset":"PRESET4"}` | `soundPreset` | **FIXED** |
| `hardware/sound-preset-mode` | `{"presetmode":1}` | `soundPresetMode: String` | **FIXED** (Int, not String) |
| `alexa/ascending` | `{"ascending":true}` | matches | ok |
| `alarms` | see below | matches | ok |
| `alarms/sounds` | 20 filenames | matches | ok |
| `hardware/light-sensor` | `{"sensor":1414}` | `lightSensor` | **FIXED** |
| `hardware/day-mode` | `{"isDayMode":true}` | `dayMode` | **FIXED** |
| `hardware/high-to-low-transition` | `{"transition":230}` | `highToLowTransition` | **FIXED** |
| `hardware/low-to-high-transition` | `{"transition":270}` | `lowToHighTransition` | **FIXED** |
| all four `*-brightness` | `{"brightness":N}` | matches | ok |
| all four `*-color` | `{"color":[r,g,b]}` | matches | ok |
| all three `*-sync-*` | `{"sync":bool}` | matches | ok |
| `software/weather` | `{"wsonoff":true,"location":"78852","wsmode":2}` as found | matches | ok — **now `"27.1258,-104.9118"`**, see "Weather works outside the US" below |
| `software/weather-wakeup-time` | `{"weatherwakeuptime":"10:00"}` | matches | ok — **currently `16:05`** |
| `alexa/lwa-status` | `{"status":false,"timestamp":254}` | — | ok |
| `alexa/tap-talk-tone` | `{"tone":true}` | — | ok |
| `alexa/wake-word-tone` | `{"tone":true}` | — | ok |

**READS — 6 are permanently broken (HTTP 408, ~15s each, 2/2 retries):**
`hardware/wifi-status`, `software/use-colon`, `software/colon-blink`,
`software/use-leading-zero`, `software/use-fade-time`, `software/display-seconds`.

These are 6 of the **25 endpoints in a poll cycle**, serialized through the request gate at
~15s apiece — roughly **90 seconds of dead time per poll**. This is almost certainly the
dominant remaining latency term, and it is a *server-side* fault we cannot fix from the app.
Note `software/use-colon` **accepts writes** (PUT → 200 in 406ms) while its GET always times
out: the app can set the colon but never read it back, and pays 15s every poll trying.

> **These six are not reliably dead — see open issue 7.** They were classified from repeated
> probes, but a later sweep found `software/weather` 408 then 200 on consecutive calls and
> `software/use-colon` 408 at 15 s *in the same sweep that succeeded elsewhere*. A 408 is a
> statement about one request, not about the route.

### Weather works outside the US — the official app's ZIP box is a UI limit

Measured on `Doppler-10caaebb`. The clock was displaying weather for **78852 (Seguin, Texas)**,
not the Mexican location it had been set to.

**`location` is free-form passthrough. The clock validates nothing.** All four of
`27.1258,-104.9118`, `33980`, `Jimenez, Chihuahua, Mexico` and `78852` were written and read
back **byte-identically**. The value goes straight to weatherapi.com's `q` parameter, which
accepts a city name, `lat,lon`, a US ZIP, a UK/Canada postal code, a METAR, an IATA code or an
IP. weatherapi.com documents **no Mexican postal codes** — but it *does* accept coordinates, so
the official app's ZIP-only input is a **UI restriction, not a protocol limit**.

**Confirmed working:** `location="27.1258,-104.9118"`, `wsmode=2`, `wsonoff=true` → the clock
displayed **20° and cloudy**, against Open-Meteo's 19.5 °C daily max / WMO 3 overcast for those
coordinates. Previously Seguin: 26.6 °C, WMO 1 mainly clear. The icon changed too, so this is
genuine data rather than a coincidence.

> **The trap: postal codes are ambiguous and the clock will not warn you.** `33980` is both the
> Mexican postal code for **Jiménez, Chihuahua** and a valid US ZIP for **Port Charlotte,
> Florida**. Send the bare string and you get US weather *in silence* — wrong data, no error.
> `33987` is in the same Florida `339xx` block. This is why the app resolves typed input to
> coordinates before writing and never sends raw user text.

**`wsmode` is three choices in one integer** — provider, statistic, unit — so the number alone
means nothing. Mapping recovered from `doppyler` 0.0.20's `WeatherMode`, the Python client
pinned by Palo Alto Innovation's own `ha-doppler` integration:

| `wsmode` | Provider | Statistic / unit |
| :--- | :--- | :--- |
| 0 | — | off |
| 1, 2 | weatherapi.com | daily high °F / **°C** |
| 3, 7, 8 | weatherapi.com | daily humidity avg / min / max |
| 4 | weatherapi.com | daily AQI |
| 5, 6 | weatherapi.com | daily low °F / °C |
| 9, 10 | weatherapi.com | hourly temperature °F / °C |
| 11, 12 | weatherapi.com | hourly humidity / AQI |
| 13, 14 | **US NWS** | daily forecast °F / °C |
| 15, 16 | **US NWS** | hourly observation °F / °C |
| 17 | **US NWS** | hourly humidity |

Modes 1–12 **resolve worldwide**; 13–17 are **US-only** and cannot resolve a non-US location.
Modes 2 and 14 are the same statistic and unit differing only in provider — so 14 in Mexico
cannot work, while 2 can.

> **The US-only claim for 13–17 is read from `doppyler` source, not measured.** Only mode 2 has
> been confirmed on this unit. Nobody has sent `wsmode=13..17` with `location` set to
> `27.1258,-104.9118` and observed the failure. The `WeatherMode` picker labels those modes as
> US-only on the strength of that inference, which is a weaker claim than every other row in
> this table. See open issue 7.

**`weatherwakeuptime` is a forecast announcement alarm, not a refresh interval.** The clock
updates its temperature and icon as soon as new data reaches it; this time is when it *speaks*
the forecast (confirmed by the owner from direct observation of the official app). Consequence:
a mode showing the **daily high** evaluated at 10:00 freezes a mid-morning value, so the wakeup
time belongs in the evening.

### The clock timezone was silently wrong

The unit shipped set to `Canada/Saskatchewan`. Corrected to `America/Chihuahua` and read back
successfully — and **the displayed time did not move at all**, because both zones are UTC−06:00
with no DST. A correct clock face is therefore **not** evidence of a correct timezone. `doptime/offset`
reported `0` throughout, which is unrelated to the zone.

### Plain-text place lookup — provider split is load-bearing

Typed place names and postal codes resolve through **two** providers, because one cannot do both:

| Input | Provider | Why |
| :--- | :--- | :--- |
| Town / city name | **Open-Meteo geocoding** (`geocoding-api.open-meteo.com`) | free, no key, global, returns an IANA timezone |
| Numeric (postal code) | **Nominatim** (OpenStreetMap) | Open-Meteo answers `33980` with *"Pola de Laviana, Spain"* and *"Audenge, France"* |

Nominatim additionally returns **every country claiming the code** — unfiltered `33980` yields
both *Jiménez, Chihuahua, México* and *Port Charlotte, Florida* as separate candidates, which is
exactly the disambiguation a user needs. `countrycodes=mx` narrows it to the right one.

Both return *all* candidates rather than guessing, because `"Jimenez"` alone matches **eight**
places across Mexico, Spain, the Philippines and Costa Rica. Note Open-Meteo also returns two
Jiménez entries for `Jimenez, Chihuahua, Mexico` — the **municipality centroid (28.333, −105.4)
and the town (27.117, −104.95)**, ~135 km apart, so picking the wrong one gives wrong weather.

**WRITES — read-only, HTTP 500 on any body (correct or invented):**
`hardware/sound-preset`, `hardware/sound-preset-mode`, `hardware/day-mode`,
`software/display-seconds`. `setSoundPreset()` exists in the API layer but is never called
from the UI, so there is no dead write path to remove.

**WRITES — confirmed working** (each changed, read back, then restored):
`hardware/volume` (`{"volume":N}`), `hardware/high-display-color`
(`{"color":[r,g,b]}`), `hardware/high-to-low-transition` + `low-to-high-transition`
(`{"transition":N}` — **not** the documented long name, which 500s), `hardware/sync-high-low-color`,
`software/use-colon` (`{"on":true}`), `software/weather`, `doptime/offset`,
`alexa/tap-talk-tone`.

**Overrides — all three exist, all three are one-shot with `GET → 404`:**
`hardware/display-text`, `hardware/small-display-digits`, `hardware/display-dots`. All
returned 200 to PUT. This confirms the `ACCEPTED_UNVERIFIED` verdict is the honest ceiling:
there is no read-back to verify against, so they can never be `SUPPORTED`.

### The silent-default trap

`DopplerLocalApi` decodes with `ignoreUnknownKeys = true`. A model whose property name does
not match the wire field therefore does **not** throw — it deserialises to the property's
**default**. Six polled values were permanently showing fabricated defaults (light sensor stuck
at 0, both thresholds stuck at 35/45, sound preset stuck at "Flat") while looking entirely
plausible. Any future field rename will fail the same silent way; `WireSchemaTest` now pins
every wire name against a verbatim live payload.

---

## Cloud Latency: The Real Root Cause (read before touching timeouts)

**Symptom:** erratic 2s–30s delay before a control action reaches the clock. **Cloud mode
only.** Confirmed on device by the user; the LAN path never showed it.

This was misdiagnosed twice from source reading before it was measured. The mutex
serialization was a *contributing* factor, not the cause. Two cloud-only defects were the
real problem, and both were invisible in the LAN path:

1. **`readTimeout(30s)` on the cloud client** (`DopplerCloudApi`). The LAN client uses 8s.
   A poll read that stalled held `requestGate` for up to 30 seconds. **Priority ordering
   cannot help here** — the gate only orders *queued* waiters, so a request already
   *executing* blocks everyone behind it regardless of priority. This is the "solid 30
   seconds". Fixed with per-call deadlines: `BACKGROUND` 5s, `INTERACTIVE` 10s.
   A stalled poll value is worthless — the next poll is seconds away — so abandoning it
   fast and freeing the gate is strictly better than waiting it out.
2. **Token-refresh storm on a dead refresh token.** The 401 branch called
   `refreshTokenProvider()` inline, per request, *inside the gate*. When the refresh token
   is dead, every one of the poll reads paid a failed refresh round-trip (itself
   30s-timeout) before retrying and failing again. Fixed with **fail-fast**: one failed
   refresh disables further attempts for the session, plus single-flight dedup.
   Note a *successful* refresh was never the problem — the token is written back and later
   requests are accepted, so only the failure path stormed.
3. **Poll cycle longer than its own interval.** 25 sequential WAN round-trips exceed the
   8s interval, so a fixed delay left the poller running back-to-back with no idle gap.
   `startPolling` now yields `max(interval, elapsed)` so the link always gets a breather.

### Measure, do not infer

`RequestTelemetry` (see File Map) records duration, outcome, and the slowest path for the
last 64 requests, surfaced on the Diagnostics screen as median/max/last plus timeout, HTTP
error, and token-refresh counters. **Check these numbers before theorising about latency
again.** Two wrong diagnoses in a row is what motivated adding it. The first two attempts
("the mutex serializes the poll", then "priority ordering should have fixed it") were both
reasoning from source with no wall-clock data.

### Still unverified

The fixes above are unit-tested against MockWebServer stalls and 401s, but **nobody has
confirmed the device latency is actually gone.** If it persists, read the Diagnostics
latency card first: high median means the relay is the bottleneck (structural — trim the
poll); high max versus low median means individual requests stall (per-request deadline or
relay throttling).

---

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
| `/software/weather` | GET/PUT | Weather config. **`location` is free-form passthrough, never validated** — send coordinates, not a postal code. `PUT` replaces the **whole** object, so a partial write cannot exist |
| `/software/weather-wakeup-time` | GET/PUT | Time the clock **announces** the forecast. Not a refresh interval |
| `/doptime/timezone` | GET/PUT | IANA zone. **Was silently `Canada/Saskatchewan` on a Mexican unit** |
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

- `DopplerRepository.refresh()` issues **25 sequential GETs**. Under a plain fair mutex, a user action issued mid-sweep queues behind every remaining poll request. Over `control.sandmandoppler.com` that produced a measured **10–20 s delay** before a volume change was even sent. That is the reason this gate exists — do not "simplify" it back to a `Mutex`.
- `INTERACTIVE` = user-initiated (slider commits, toggles, alarm edits, probes). Served ahead of queued polls.
- `BACKGROUND` = the periodic poll. Yields to anything interactive.
- **Every getter must go through `executePollRequest(path)`** (there are 34 such getters), never `executeAuthenticatedRequest("GET", ...)`. Centralizing it is deliberate: tagging getters by hand is how one gets missed, and a single stray interactive read reintroduces the lag. `RequestSerializationTest` pins it. Of those 34, exactly **25 are called by `refresh()`** — so the poll sweep is 25 requests even though the getter surface is wider.
- The one exception is `executeConfirmRequest(path)` — the post-write read-back used to confirm a value actually landed (e.g. firmware clamping brightness). It is interactive because it exists to reflect a user action immediately.
- New endpoints: writes default to `INTERACTIVE` (the default parameter), so only new *getters* need attention.

### Cost of serialization

Serializing is not free: a poll cycle is now the sum of 25 round-trips. On a slow link that can exceed the poll interval, so the poll is effectively always running. Priority ordering keeps the user responsive, but if latency stays high, the fix is to trim the poll (fewer endpoints, adaptive interval, or skip reads while the user is actively dragging) — not to weaken the gate.

**This is now partly paid down.** The six permanently-408 paths are skipped by `EndpointCapabilities` (commit `417ca63`), which removes ~90 s from every cycle and takes the sweep from 25 attempted requests to 19. Callers get `DopplerException.UnavailableException` rather than a fabricated default, and the set is published as `DopplerDeviceState.unavailableEndpoints` so the UI says "unavailable" instead of "zero". See open issue 2 for why the "permanently" part is not yet earned.

**Not every socket goes through the gate.** `LocationResolver` calls Open-Meteo and Nominatim — unrelated third-party internet hosts that never touch the hardware. Serializing those behind the clock gate would make a place lookup wait on a poll sweep for no benefit. The invariant protects the *hardware* from concurrent requests, not every network call in the app.

## Verdict Taxonomy (for probeOverrideSupport)

- `ACCEPTED_UNVERIFIED` — clock/relay answered 2xx. **This is the expected result for the two display overrides and it does not prove anything rendered.** `hardware/display-text` and `hardware/small-display-digits` are one-shot display commands with no GET counterpart, so there is nothing to read back, and the cloud relay returns 2xx for requests the clock never acts on. Only the clock face can confirm.
- `SUPPORTED` — reserved for overrides that expose a GET, where the stored value was read back and matched. The two display overrides cannot reach this verdict.
- `REJECTED` — clock routed the request but refused the payload (400/422); fix is on payload side. **Do not report this as a firmware gap** — the route exists, so it is never a missing-implementation problem.
- `NOT_SUPPORTED` — clock has no route for the endpoint at all (404/405/501); this *is* a firmware gap
- `UNKNOWN` — unhandled HTTP status (500, 503, etc.)

### Why a 2xx is not proof

A `PUT` to `hardware/display-text` returning 2xx tells you the *route exists*. It does not tell you the firmware implements the override, nor that the cloud relay forwarded it. Observed in practice: probe returned 2xx, no text appeared. Treat `ACCEPTED_UNVERIFIED` as "worth trying", not "works".

## Read This First: Repo State (as of commit `c7d5b17`)

`main` is clean and **synced with `origin`** (`git rev-list --left-right --count origin/main...HEAD`
→ `0 0`). Test suite: **174 tests across 17 suites**, all green, 0 skipped, 0 failures.

This section used to claim "46 tests across 5 suites" and "every cloud path in this doc is
unverified against a live clock". Both are now false — the doc was 4 commits and 128 tests
behind. Read the findings sections above before trusting any "still open" label.

**Verified against the real clock (hardware, `Doppler-10caaebb`):**

- Weather end to end. `location="27.1258,-104.9118"`, `wsmode=2`, `wsonoff=true` → display
  showed 20° and cloudy, matching Open-Meteo's 19.5 °C daily max / WMO 3. Icon changed from the
  previous Seguin reading, so this is genuine data.
- Timezone write and read-back: `Canada/Saskatchewan` → `America/Chihuahua`, HTTP 200.
- `POST /alarms` is create-only; editing an alarm duplicates it.
- Active alarms report `status: 10`, not `1`.
- LAN is unusable: `192.168.11.107` answers ICMP, no open TCP port, no 5443.
- The endpoint access matrix above (30 working reads, 4 read-only writes, 3 one-shot
  overrides, 6 dead reads), including the six wire-schema fixes.
- `location` accepts arbitrary free-form text and validates nothing.

**Verified by automated tests only:**

- The entire **Weather screen**, the **colour picker UI**, and the **Diagnostics card**. These
  have never been opened by a human on the device. `LocationResolver`, `WeatherMode`,
  `ClockTime`, `PlaceCandidate` and the weather repository writes are unit-tested against
  recorded payloads and mock clocks.
- `wireName`/`WireSchemaTest` pins every wire field against a verbatim live payload.
- `RequestSerializationTest` (max concurrent == 1, priority ordering), probe verdict taxonomy,
  optimistic rollback, drag coalescing, the 408/unavailable-endpoint path.

**NOT verified — needs a human on real hardware. Do not report these as done:**

- **The 10–20 s action lag fix has still never been measured end-to-end.** `RequestGate` is
  unit-tested for *ordering*, not duration. Root cause was identified as the 30 s cloud read
  timeout plus a token-refresh storm, both fixed and unit-tested against MockWebServer — but
  nobody has confirmed the device feels fixed. If it persists, read the Diagnostics latency
  card before theorising.
- **The two display overrides were never observed rendering.** `hardware/display-text`,
  `hardware/small-display-digits` and `hardware/display-dots` return 200 to PUT and 404 to GET.
  `ACCEPTED_UNVERIFIED` is the honest ceiling and only the clock face can raise it.
- **The clock's on-device duplicate alarms still need deleting** (`id: 2` and `id: 3`, both
  13:15, created by the create-only POST bug). The app is fixed; the data is not.

## Open Issues — Do Not Assume Any of These Are Fixed

Resolved since the last revision of this list, struck so they stop being re-investigated:
**custom colours** (commit `d0beadf` added `ColorSelector` with a free-form dialog alongside
the 5 presets — the reported bug was never diagnosed, the capability simply did not exist), and
**the `DragCommitGate` KDoc** (rewritten; it no longer claims "at most once per window", and the
implementation is unchanged trailing-edge, which the new text now describes accurately), and
**alarm write path regression from c9e62e8** (conditional `refresh()` at start of
`addOrUpdateAlarm()` ensures `clockAlarmIds` is fresh before create-vs-update routing
decision, preventing duplicate alarms from POST-creating instead of PUT-updating);
this fix was verified with 6/6 `AlarmWriteProtocolTest` tests and full suite green).

1. **Poll cost — partly paid down.** `refresh()` attempted 25 sequential GETs; the six dead
   paths are now skipped, so a cycle is ~19 requests and ~90 s shorter. Total load is still high
   and the interval is still fixed. `startPolling` yields `max(interval, elapsed)` to guarantee
   an idle gap, and per-call deadlines stop a stall from blocking the user. Next lever: skip
   reads while the user is actively dragging, or make the interval adaptive.
   **Do not weaken the gate to fix this.**
2. **`EndpointCapabilities` will disable working features.** This is the most serious open
   item. The six seeded paths are hard-coded as "never return" from repeated probes, but a
   later sweep caught `software/weather` 408-then-200 on consecutive calls and
   `software/use-colon` 408 at 15 s *in a sweep where other paths succeeded*. A 408 is evidence
   about one request, not about a route. The failure is now runtime-learned as well: two
   give-ups committed by `endPollCycle(healthy)` add a path to `unavailable`, and only a
   user-triggered `recheckAll()` ever clears it. So a feature can be switched off permanently by
   two unlucky polls and stay off. **Do not widen the seed set.** Fixing this properly needs a
   sweep that records status and timing per path across many cycles, then a model that
   distinguishes intermittent from absent.
3. **404 is discarded, and 404 is the strongest evidence available.** A nonexistent route
   answers `404 "No mapping for HTTP-method"` in ~240 ms — instant, unambiguous, and proof the
   route does not exist on this model. A stalled handler answers `408 "No response from
   Doppler"` at ~15 s. The two are cleanly separable and `isEndpointGiveUp` recognises **only
   the 408**. That is the safe direction for not disabling features, but it means the one
   unambiguous signal of absence is unused, and intermittent 408s are indistinguishable from
   absence. Related: the six real 404s on this model (`software/colon`,
   `software/nightlight-color`, bare `doptime`, `software/display-order`,
   `software/temperature-unit`) are recorded in this doc but not in the code.
4. **Drafts are dropped on the optimistic echo, not on hardware confirmation.**
   `DashboardScreen.kt` and `DisplayLightingScreen.kt` clear the local draft as soon as the
   repository's *own optimistic value* comes back. That is self-confirmation: it proves nothing
   about the clock. A rejected or clamped write therefore clears the draft and the UI shows the
   optimistic value until the next poll corrects it. Proper fix is to clear the draft when
   `confirmHardware` reports the real stored value.
5. **`confirmHardware` covers one endpoint.** Volume only. The 6 brightness/threshold writes and
   the 4 colour writes all echo the optimistic value and never read back, so firmware clamping
   is invisible on all of them. Open sub-question: PUT an out-of-range brightness, then GET it,
   to find out whether the firmware clamps at all.
6. **`CLAUDE.md` is stale.** Line 35 describes `DopplerLocalApi` as "Mutex-serialized"; it is
   now `RequestGate`, and the word `RequestGate` does not appear in the file at all. The repo
   layout also omits `api/LocationResolver.kt`, `model/WeatherMode.kt`,
   `model/PlaceCandidate.kt`, `model/ClockTime.kt`, `storage/WeatherPlaceStore.kt`,
   `api/EndpointCapabilities.kt`, `ui/screens/WeatherScreen.kt` and `ColorSelector.kt`.
   AGENTS.md rule 5 describes the lock as a coroutine `Mutex` too.
7. **`wsmode` 13–17 US-only is inferred, never measured.** See the note in the weather section.
   One probe with `location="27.1258,-104.9118"` and modes 13, 14, 15, 16, 17 would either
   confirm the label or expose that the NWS provider resolves Mexico fine, which would change
   which modes the picker offers.
8. **`weatherwakeuptime` is `16:05`, chosen while probing, not chosen.** With `wsmode=2` (daily
   high) an evening announcement is right; the value was never discussed with the owner.
   Restore `10:00` or confirm the evening slot.
9. **Cloud-vs-LAN precedence in `buildApi()` is untouched and unexamined.** Deliberately left
   alone, listed so nobody assumes it was decided.


## The UI Write Path — Do Not "Simplify" This Away

Three layers cooperate to keep sliders responsive. Removing any one reintroduces jitter.

1. **`DragCommitGate` (250 ms trailing edge).** Coalesces a drag burst into one write via a
   `Channel.CONFLATED`, so a 60 fps drag does not queue 600 requests at the single-threaded
   daemon. Wired to Dashboard volume and all 6 DisplayLighting sliders.
2. **Local draft state.** `DashboardScreen.kt:36` (`volumeDraft`) and
   `DisplayLightingScreen.kt:64` (`drafts` map) hold the in-flight value so the thumb tracks
   the finger instead of waiting on a round-trip.
3. **Optimistic repository update.** `applyOptimisticUpdate` publishes the new value instantly
   and rolls back to the previous state on API failure.

**The trap:** layers 1 and 3 alone make the UI look instant even when the write is queued or
failing. That is why "the sliders feel great" and "the change takes 20 s to reach the clock"
can both be true at once. Do not diagnose slider lag as a UI problem — check where the write
actually sits in `RequestGate`.

**Adding mid-drag commits** is deliberately *not* done. It would multiply writes during a drag
and compete with the poll. If you implement it, keep them at `RequestGate.Priority.INTERACTIVE`
and expect to revisit the poll-trimming work above.

## Failure Modes Learned Here — Generalize These

- **Serializing is not the same as prioritizing.** Adding a plain `Mutex` in front of a
  background sweep satisfies the hardware invariant and *still* ships a 10–20 s user-visible
  regression, because a fair mutex serves the sweep first-come-first-served. Whenever you add
  mutual exclusion, ask what the background workload is and whether it must yield.
- **Never infer behaviour from an HTTP status alone.** A 2xx from `control.sandmandoppler.com`
  proves the *route exists*; the relay answers 2xx for requests the clock never acts on. If a
  verdict cannot be confirmed by reading state back, say so in the verdict itself — do not let
  the UI imply a confirmation you never made. This is why `ACCEPTED_UNVERIFIED` exists as a
  separate verdict from `SUPPORTED`.
- **Do not invent a specific value you did not observe.** The first probe reported
  `httpCode = null` on success (OkHttp's `isSuccessful` path never surfaces a code) and the UI
  rendered that as "no HTTP response". Reporting the *range* ("HTTP 2xx") is honest; fabricating
  `200` is not.
- **Optimistic UI + eager confirmation can hide failures.** An echo of your own optimistic value
  looks identical to hardware confirmation. See open issue 4.
- **"Times out" is not a property of an endpoint, it is a property of a request.** Six paths
  were seeded as permanently dead and the app now refuses to poll them at all — but a later
  sweep found `software/weather` 408-then-200 on consecutive calls. Treating an observed
  failure as an observed *fact* about the route is the same error as inferring a 2xx means the
  firmware did something, just in the pessimistic direction. Sample repeatedly before you
  convert an observation into a permanent skip.
- **404 and 408 are different facts.** `404 "No mapping for HTTP-method"` in ~240 ms means the
  route does not exist. `408 "No response from Doppler"` at ~15 s means a handler accepted the
  connection and produced nothing. Treating both as "not supported" loses the one signal that
  is actually conclusive.
- **When you regress something, lead with the root cause and the fix**, not a reassurance. The
  lag regression was reported after a commit that claimed to fix jitter; the honest answer was
  "that was mine, here is exactly why, here is the test that pins it."

## Working in This Repo

**Quality gate — required before claiming anything is done:**

```bash
cd android && ./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

Both must exit 0. Test counts are readable from
`android/app/build/test-results/testDebugUnitTest/*.xml`. Also run
`./node_modules/.bin/tsc --noEmit` if you touched `server.ts`.

**Toolchain gotchas that have each cost a build cycle:**

- **JUnit requires void test methods.** A Kotlin test whose body ends in a Truth assertion
  infers a non-`Unit` return type and fails at class-init with
  `InvalidTestClassError: ... should be void`. Write `fun \`name\`(): Unit = runBlocking { ... }`.
- **A concurrent `launch`/`async` ordering assertion is not deterministic.** Background waiters
  enqueue in scheduling order, not launch order. Assert the *invariant* (the user request is
  served first) rather than `containsExactly(...).inOrder()` across concurrently-launched peers.
- **Git commit messages must go through a file.** PowerShell here-strings with `git commit -F -`
  fail with a `pathspec ... did not match` error. Write to a temp file, then
  `git commit -F <path>`.
- **Poll interval is 8000 ms in practice** (`DopplerViewModel` calls
  `startPolling(intervalMs = 8000L)`), even though `DopplerRepository.startPolling`'s default
  parameter is `5000L`. Trust the call site, not the default.

**Testing patterns that actually catch things here:**

- `MockWebServer` with a custom `Dispatcher` that tracks **peak concurrency** is how the
  serialization invariant is pinned. Assert `maxConcurrent == 1`.
- **Mutation-verify your tests.** After writing an assertion, flip the behaviour it claims to
  pin and confirm the test fails, then revert. This caught a probe-taxonomy assertion that
  passed for the wrong reason.
- Assert the *absence* of a fault where it matters: a test that a 500 does not poison the gate,
  wrapped in `withTimeout`, because a leaked lock would otherwise hang the suite instead of
  failing it.
- Prefer centralizing a rule over applying it by hand. The 34 getters were routed through
  one `executePollRequest(path)` helper specifically because hand-tagging 34 call sites is how
  one gets missed.

## File Map — Where Things Live

| Path | Role |
|------|------|
| `api/DopplerLocalApi.kt` | LAN transport, 67 endpoint methods, owns `requestGate` |
| `api/DopplerCloudApi.kt` | Cloud transport; overrides `executeAuthenticatedRequest` + `buildUrl` |
| `api/RequestGate.kt` | Priority gate. Read the KDoc before touching concurrency |
| `api/EndpointCapabilities.kt` | Skips read paths measured as never answering. **Suspect — see open issue 2** |
| `api/RequestTelemetry.kt` | Last-64 request durations/outcomes. **Check Diagnostics before diagnosing latency** |
| `api/LocalTokenManager.kt` | Nonce/`localKey` Bearer derivation, has its own separate mutex |
| `repository/DopplerRepository.kt` | The 25-request `refresh()` poll, optimistic updates, `probeOverrideSupport()` |
| `viewmodel/DopplerViewModel.kt` | UI state, poll interval (8000 ms) |
| `ui/DragCommitGate.kt` | Trailing-edge write coalescing (250 ms) |
| `ui/screens/DiagnosticsScreen.kt` | Transport/link reporting, override probe card, `OverrideVerdict.color()` |
| `model/DopplerModels.kt` | Wire models, `OverrideProbeResult`, `OverrideVerdict`, `DopplerColor` (5 presets + `fromHexOrNull`) |
| `ui/screens/ColorSelector.kt` | Preset swatches plus a free-form hex dialog (commit `d0beadf`) |
| `api/LocationResolver.kt` | Typed place name / postal code → coordinates. Open-Meteo for text, Nominatim for numeric |
| `model/WeatherMode.kt` | The `wsmode` decode: provider + statistic + unit, with plain-language labels |
| `model/PlaceCandidate.kt` | A resolved place; `coordinateString` is **what actually reaches the clock** |
| `model/ClockTime.kt` | `HH:mm` parse/format for `weatherwakeup-time`. Rejects `"+10:00"` and `"24:00"` |
| `storage/WeatherPlaceStore.kt` | Remembers the place **name** — the clock keeps only coordinates |
| `ui/screens/WeatherScreen.kt` | Weather tab. Built for elderly users: no coordinates typed, no raw `wsmode` |
| `MainActivity.kt` | `buildApi()` cloud-vs-local selection, cloud token refresh |

---

*Compiled from doppyler==0.0.20 source reverse‑engineering, the official Postman collection (portals.docsie.io), and live traffic against `control.sandmandoppler.com`. Intended as a knowledge center for future AI agents working on this project.*