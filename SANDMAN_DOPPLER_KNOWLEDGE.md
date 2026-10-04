# Sandman Doppler Knowledge Center

> **Start here if you are a fresh agent session:** jump to **Live Hardware Findings** (what
> the clock actually does — `POST /alarms` is create-only, `status` is 10, LAN is dead), then
> **Feature Minimum Software Versions** (why 15 of 16 official features do not exist on this
> `1214` firmware — this explains most "broken function" reports, and firmware can never be
> updated because Mender is shut down), then
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

> **These six are now explained, not blamed.** All six are version-gated features that postdate
> this clock's `1214` firmware — see *Feature Minimum Software Versions*. Read that section before
> treating any of this as a relay fault or as something a retry could fix.

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

> **Correction (Oct 2026):** none of these three appear anywhere in the official app's endpoint
> surface. The real lightbar control is `software/use-rainbow-display`, and it is version-gated
> behind `RAINBOW_MODE` (min 1662). So "the route exists but the firmware ignores it" is the wrong
> reading — the routes were never part of this protocol. See *Feature Minimum Software Versions*.

### The silent-default trap

`DopplerLocalApi` decodes with `ignoreUnknownKeys = true`. A model whose property name does
not match the wire field therefore does **not** throw — it deserialises to the property's
**default**. Six polled values were permanently showing fabricated defaults (light sensor stuck
at 0, both thresholds stuck at 35/45, sound preset stuck at "Flat") while looking entirely
plausible. Any future field rename will fail the same silent way; `WireSchemaTest` now pins
every wire name against a verbatim live payload.

---

## Feature Minimum Software Versions — and why it is NOT a capability predictor

> **Read the correction first.** An earlier revision of this section claimed all fifteen gated
> features were therefore absent from this clock. **That was wrong, and it was an inference dressed
> up as a finding.** The gate table below is real, but it does not predict what the firmware can do.
> The measured table further down is the one to trust.

The official app (1.5.1) contains a hardcoded feature gate: `com/sandman/app/model/VersionControlHelper$Features`,
an enum whose every constant carries a `minimumVersion`. `featureAvailable(f)` is simply
`softwareVersion >= f.minimumVersion`, where `softwareVersion` is parsed out of `GET /device`'s
`software` field — `"0.1214 Bucky"` → `split(".")[1]` → `"1214 Bucky"` → `split(" ")[0]` → **`1214`**.

Recovered verbatim from the dex (`.clinit` of the enum):

| `Features` constant | min `softwareVersion` | Gate says | **Measured on 1214** |
| :--- | ---: | :--- | :--- |
| `STOP_SNOOZE_ALARM` | 1231 | absent | untested (see caveat) |
| `REAL_TIME_WEATHER` | 1232 | absent | **WORKS** — 200 |
| `SNOOZE_LENGTH` | 1347 | absent | **absent** — 0/6 |
| `TIME_OFFSET` | 1348 | absent | **WORKS** — 200 |
| `SMART_BUTTONS` | 1380 | absent | **WORKS** — 200 (`button1`, `button2`) |
| `HIDE_COLON` | 1483 | absent | **absent** — 0/6 GET (PUT answers 200) |
| `HIDE_LEADING_ZERO` | 1483 | absent | **WORKS** — 4/6, intermittent |
| `WIFI_INFO` | 1544 | absent | **absent** — 0/3 |
| `LOCAL_CONTROL` | 1544 | absent | LAN unreachable; cause unknown |
| `DISPLAY_SECONDS` | 1544 | absent | **absent** — 0/3 |
| `DISPLAY_SNOOZE` | 1556 | absent | **absent** — 0/3 |
| `FADE_MODE` | 1557 | absent | **absent** — 0/3 |
| `US_WEATHER` | 1569 | absent | untested (needs `wsmode` 13–17, open issue 7) |
| `BUTTON_ENDPOINTS` | 1629 | absent | untested (see caveat) |
| `RAINBOW_MODE` | 1662 | absent | **absent** — 0/6 |

**Five gated features work. Eight are genuinely absent. Two remain untested.**

### Why the gate table cannot be used to skip endpoints

The decisive evidence is that **adjacent constants have opposite outcomes**:

- `SNOOZE_LENGTH` = **1347** → absent (0/6)
- `TIME_OFFSET` = **1348** → **works**

One build apart, opposite results. If these were firmware capability markers, that is impossible. So
the constants are almost certainly **app-release markers** — the firmware version at which the
vendor's *app* began offering the UI — not the version at which the *device* gained the capability.
Several capabilities clearly shipped in firmware before the app exposed them.

**Therefore: do not gate polling on `softwareVersion`.** Gating on the gate table would skip
`doptime/offset`, `hardware/button1`, `hardware/button2`, `software/weather` and
`software/use-leading-zero` — five endpoints that demonstrably answer. The earlier suggestion to
"gate on `softwareVersion` from `/device` instead of on accumulated 408s" (open issue 10) is
**withdrawn as wrong.**

### The real lesson: 408 means intermittent, and we seeded on it

`software/use-leading-zero` is **in the `MEASURED_UNAVAILABLE` seed list**
(`api/EndpointCapabilities.kt:88`) and therefore **permanently skipped by `refresh()`** — yet it
answers `200 {"on":false}` on **4 of 6** attempts. **`hardware/button-last-message` is not even
modelled by this app and behaves the same way: 3 of 6 answered `200 {"message":""}`.**

Both were classified absent from 2/2 probes. That is the same error the doc warns about elsewhere,
now demonstrated with a concrete victim: **the optimisation from commit `417ca63` has switched off a
feature that works two times in three.** Open issue 2 is no longer theoretical.

The pattern in the raw timings is distinctive and worth recognising: a successful answer takes
**~3–5 s**, while every failure costs the full **~15 s**. A 408 is not "absent", it is "slow", and
these handlers are evidently loaded — competing with each other, or with the relay.

### The six "dead" endpoints — measured, not inferred

The original claim was that these six return 408 forever and therefore are features this build
lacks. **Re-probed at 3–6 attempts each, serialized, Oct 2026:**

| Endpoint | Attempts | Result | Verdict |
| :--- | :--- | :--- | :--- |
| `software/use-colon` | 6 | 408 ×6 | **absent** (but `PUT` → 200, see below) |
| `software/colon-blink` | 6 | 408 ×6 | **absent** |
| `software/use-fade-time` | 3 | 408 ×3 | **absent** |
| `software/display-seconds` | 3 | 408 ×3 | **absent** |
| `hardware/wifi-status` | 3 | 408 ×3 | **absent** |
| `software/use-leading-zero` | 3, then 6 | 408, TO, **200**, then 4/6 × 200 | **WORKS — intermittent** |

**Five of the six are genuinely absent. One — `software/use-leading-zero` — is alive and answering
two attempts in three.** It is seeded as unavailable and permanently skipped. That is a live bug.

`software/use-colon` accepting a `PUT` (200 in 406 ms) while its `GET` is 0/6 remains unexplained.
The gate table does not explain it either, since `HIDE_LEADING_ZERO` shares the same 1483 value and
*does* answer on `GET`. Most likely the write is accepted and discarded by a relay that has no
upstream handler, which is the `ACCEPTED_UNVERIFIED` trap again — treat a 200 on that `PUT` as
meaning nothing until the clock face confirms it.

> **Actionable consequence for `EndpointCapabilities`:** `software/use-leading-zero` must come out of
> `MEASURED_UNAVAILABLE` (`api/EndpointCapabilities.kt:88`) or be made recoverable, and
> `hardware/button-last-message` (3/6 answering, unmodelled) is worth adding. See open issue 2 and
> open issue 10.

### And the lightbar / display overrides

`RAINBOW_MODE` (min **1662**) is the gate behind the 29-LED lightbar. Our probe sent six payloads to
`hardware/display-dots`, five got 200 and one got 417, and nothing rendered. Two separate errors
were in play there:

1. **Wrong endpoint.** The official app's lightbar toggle is `software/use-rainbow-display`
   (`GET`/`PUT`, body `RainbowModeObject`). `hardware/display-dots` appears **nowhere** in the
   official app's entire endpoint surface. A 200 from an endpoint the official app does not use is
   the same `ACCEPTED_UNVERIFIED` trap as `display-text` — the relay answered, nothing was addressed.
2. **Wrong firmware.** Even at the correct path, rainbow mode did not exist until 1662.

`hardware/display-text` and `hardware/small-display-digits` are likewise **absent from the official
app's endpoint list entirely**. Treat all three "override" endpoints as invented, not as
unimplemented firmware. See open issue 11.

## The Firmware Update Path — Mender, not a REST download

The official app does **not** download a firmware file. It asks the Mender OTA server to stage a
deployment, and the clock's own Mender client pulls the artifact. Three pieces, all recovered from
`com.sandman.app.network.CustomNetworkService` (Retrofit annotations) and
`DopplerInfoActivity.checkForAvailableUpdates()`:

### 1. Discover the builds — a plain text file, not the cloud API

```
GET https://ofpai.com/doppler/latest.txt
```

Fetched live; the file has **four** lines:

```
zeus_imx6ull_release-1714
zeus_imx6ull_release-1860
zeus_imx6ull_release_force-1716
zeus_imx6ull_release_force-1859
```

The app splits on `\n` and reads **index 0 as `updateVersion`** and **index 1 as `betaVersion`** —
so the stable BuildID is `zeus_imx6ull_release-1714` and the beta is `zeus_imx6ull_release-1860`,
matching the `1714` / `1860` seen in the app's UI. Lines 2 and 3 (the `force` variants, 1716 and
1859) are **not read by app 1.5.1** — do not assume any other app version ignores them either.

Version comparison is `line.split("-")[1].toIntOrNull()` → `1714`, compared against the device's
own `softwareVersion` (1214). Release notes come from
`https://ofpai.com/doppler/release_notes-<build>.txt`, and are also baked into the app as
`appReleaseNotes` for versions 1.3.9 → 1.5.1.

> `zeus` / `imx6ull` is the hardware platform: a NXP i.MX6ULL. The BuildID is a **Mender artifact
> name**, not a clock endpoint.

### 2. Stage the deployment — this is the call that was 404ing

```kotlin
@POST("mender/deploy")
fun createDeployment(@Body deployment: DeploymentObject): Call<Unit>

data class DeploymentObject(val BuildID: String)   // note the capital ID
```

`DeploymentApi.createDeployment(version, isBetaVersion)` posts `{"BuildID": "<line from latest.txt>"}`.
Beta is a separate button and passes the same BuildID string from index 1 — there is no flag in the
body. Analytics events are `create_deployment` / `create_beta_deployment`.

**This is why every firmware update endpoint 404'd on the cloud control API.** The 404 probe was
presumably made without the `{dsn}` prefix, or against the wrong host. The route is
`{dsn}/mender/deploy` on the same `control.sandmandoppler.com` base as everything else.

### 3. Pending-update handshake

```kotlin
@GET("hardware/update")  fun getUserUpdate(): UserUpdateObject     // {"updateInfo": "..."}
@PUT("hardware/update")  fun setUserUpdate(@Body updateInfo)         // {"updateInfo": "Approve" | "Reject"}
```

Log strings: *"Get pending update"*, *"Error setting pending update"*. `DopplerInfoActivity` shows
an Approve button and a Reject button, both implemented as `setUserUpdate("Approve")` /
`setUserUpdate("Reject")`, and writes a per-device pref keyed
`<serialNumber>_update<version>` (plus `_install_` / `_download_` states) so an in-flight install
survives an app restart. Status strings include `update_available`, `update_downloading`,
`update_installing`, `update_error`, `Update still in progress`, `Update successful`,
`Update has likely failed.`, `Update is not supported for …`.

### Why the clock updates itself, unattended

Nothing in the app pushes an image to the clock. The clock's Mender client polls the Mender server,
sees a pending deployment matching its device ID, downloads and installs it, and reboots. The app
only (a) asks Mender to create the deployment and (b) records user consent locally plus in
`hardware/update`. **This is why a firmware upgrade can be staged from the phone and survive the
phone being out of range**, and it is also why the app cannot report progress honestly — it has no
visibility into the transfer.

### Verified live against the clock (GET-only, Oct 2026)

Authenticated and probed `Doppler-10caaebb` with reads only. **No write of any kind was sent.**

| Probe | Result | Meaning |
| :--- | :--- | :--- |
| `GET /device` | 200, 979 ms, `software: "0.1214 Bucky"` | gate input is **1214**, confirmed |
| `GET /hardware/update` | **200**, 840 ms, `{"updateInfo":""}` | route **exists and answers**; no update is pending on the device |
| `GET /mender/deploy` | 404, 243 ms, `"No mapping for HTTP-method: 'GET'"` | **ambiguous — see below** |
| `GET /hardware/volume` | 200, 680 ms, `{"volume":29}` | auth sanity control passes |

Two things worth recording:

**`hardware/update` is a live device route, not a cloud-only one.** It answered in 840 ms with a
real body, so the clock itself serves it. This app does not model this endpoint at all — a gap, and
a safe one to read since `updateInfo` is currently empty.

**The `mender/deploy` 404 does not prove the route is absent.** oatpp resolves routes on exact
method+path pairs, and the app declares `mender/deploy` as **POST-only**, so a GET is *expected* to
404 with exactly this message. Confirmed the strictness directly: `GET hardware/update` returns 200
while `GET mender/deploy` 404s in the same 240 ms window, so the stack is definitely not
method-lenient.

Attempted discriminators, **both of which failed and are worth not repeating**:

- `OPTIONS` and `HEAD` return 404 in ~230 ms **for `mender/deploy`, for `hardware/update`, and for
  the known-absent `software/colon` alike**. Since `hardware/update` demonstrably exists, this
  proves oatpp implements neither verb and registers no catch-all. Those probes carry **zero**
  information here, and a 404 from them means nothing.

**So route existence for `mender/deploy` is unprovable by any safe request.** The only discriminator
left is an actual POST, which *is* the firmware install. There is no way to test this route without
performing the upgrade — which is a strong argument for letting the official app do it (below).

> **Recommendation: use the official app's Update button, not a hand-rolled POST.** App 1.5.1 is
> signed and known-good, its `createDeployment` → `setUserUpdate` ordering is the vendor's own, and
> pressing Update in Doppler Info cannot get the body shape or the BuildID wrong. The equivalent
> hand-rolled call is `POST {dsn}/mender/deploy` with `{"BuildID":"zeus_imx6ull_release-1714"}` —
> one POST, no rollback, and it succeeds or bricks.

> **What was NOT verified:** no live `POST {dsn}/mender/deploy` was attempted. Writing to
> `mender/deploy` triggers a real firmware install on the owner's clock. Do not fire it to "test"
> without explicit owner consent, and expect no rollback if the artifact is wrong.

### Why "press Update and nothing happens" tells you nothing

**RESOLVED, Oct 2026 — Mender is permanently offline.** The owner contacted the CEO of the now-defunct
Sandman company, who confirmed that **Mender has been shut down** and that the firmware image is not
available on hand. So this is not a puzzle awaiting a cleverer probe:

- `mender.sandmandoppler.com` still resolves (`52.8.109.145`) but nothing listens — the DNS record
  outlived the service. This **independently corroborates** the earlier TCP-443/80 observation, and
  the cause is *decommissioned* rather than *firewalled*. Both readings predicted the same probe
  result; the vendor has now told us which was right.
- **`POST mender/deploy` can no longer do anything, ever.** The route may well still exist on the
  clock's 1214 firmware and still return 2xx — but there is no Mender server behind it to serve an
  artifact, so the clock polls, finds nothing, and silently does nothing. That is almost certainly
  the observed behaviour, and it is **case (b)** in the table below.
- The 1714 image is unobtainable through any channel currently known. `ofpai.com/doppler/` serves
  text files only and the directory listing is 403.

**Practical upshot: this clock is pinned at firmware 1214 and always will be.** The consequence is not
abstract — see *Feature Minimum Software Versions*: fifteen of sixteen official features are gated
above 1214, so the correct engineering response is to **stop treating those endpoints as broken and
start treating them as absent**, and to hide or clearly mark the UI that depends on them. Open issues
10 and 11 are the actionable form of this.

### If the firmware is ever recovered (future work, owner's intent)

The owner intends to revisit this if the image ever surfaces. Recorded so a future session does not
re-derive any of the above. **Nothing here should be built speculatively.**

What is already known and reusable: the BuildID scheme (`zeus_imx6ull_release-<build>`), the
consent handshake (`hardware/update`, `{"updateInfo": "Approve"|"Reject"}`), the exact feature-gate
table, and that `GET mender/deploy` cannot be tested without performing an install.

**What a *safe* manual update would additionally require — none of which is known yet:**

1. **A recovery path.** An i.MX6ULL with no serial console and no documented recovery partition
   cannot be recovered from a bad flash. Until someone can answer "how do I get back?", a manual
   write is a coin flip on the user's hardware.
2. **The partition/bootloader layout.** A rootfs image is not a firmware file. Writing it needs to
   know the target partition, the boot chain, and whether the bootloader expects a specific
   rootfs version. Getting this wrong does not fail loudly.
3. **Signature verification.** Mender's normal model is that the *device* verifies a signed artifact.
   With Mender gone that check is absent — so a hand-obtained image is **unverified by
   construction**, and "it came from the internet" is the whole of its provenance.
4. **A last-known-good image to fall back to**, which is the same 1214 image currently on the device
   and would itself need capturing before any attempt.

Honest framing: the gate table makes the *value* of an upgrade clear and large, but the four items
above are the difference between a feature unlock and a bricked clock. Recommend treating items 1–3
as blocking, not as details.

### Original symptom analysis (kept for the record)

**Confirmed by the owner, Oct 2026: pressing Update in the official app does nothing at all** — no
progress, no error, no state change. This is worth understanding properly, because the obvious
reading ("the app can't do it either") is wrong and the naive alternative ("so the route is
missing") is also unsupported.

The app never fetched the file and was never supposed to. It is the **control plane**; the clock's
Mender client is the **data plane**. Being unable to download the image and being able to trigger an
install are not in conflict — different actors do different things.

**The real finding is that this button cannot report failure.** From the bytecode:

- `CustomNetworkHandler.createDeployment` is a Retrofit `await()`-style suspend call returning
  `Call<Unit>`. Any non-2xx becomes an `HttpException`, which it **rethrows**. The only branch is
  `if (code == 401)` → log `"HttpException 401 exception, should logout"` + `SessionExpired`.
  There is no response-code inspection, no retry, no user-facing error.
- `DeploymentApi.createDeployment` wraps that in `CoroutineScope(Dispatchers.IO).launch { … }` —
  **fire-and-forget, with no try/catch.** The rethrown exception dies in an orphaned coroutine and
  reaches only the crash reporter (`PapertrailLogger`).

So a failed deployment POST is **invisible to the user by construction**. "Nothing happens" is
produced identically by at least three different worlds:

| # | What actually happened | User sees |
| :--- | :--- | :--- |
| a | POST 404 — `mender/deploy` genuinely absent on 1214 firmware | nothing |
| b | POST 2xx, but the clock never polls Mender (unenrolled / expired enrollment) | nothing |
| c | POST 2xx and the clock *is* installing; the app simply cannot observe progress | nothing |

The app has no status polling for firmware at all — it writes prefs
`<serial>_update<version>-install_` / `-download_` after the call, but never reads the device back.

> **Therefore: do not read the owner's "Update does nothing" as evidence that the route exists, or
> that it does not, or that the fetch is impossible.** It is a null result. Any of (a)/(b)/(c) fits.
>
> **The decisive zero-risk test is device logging, not the API.** `Timber` is wired up, so
> `adb logcat` while pressing Update will show either `HttpException 404` (→ case a) or a clean
> return (→ b or c). That distinguishes the worlds without changing any device state.

Note this is the **fourth** time in this project that "a 2xx / a silent success" has been mistaken
for a verified outcome — `ACCEPTED_UNVERIFIED`, the optimistic draft echo, the display overrides.
The vendor app has the same blind spot we do.

### Can the firmware image be downloaded and sideloaded? No.

Attempted Oct 2026. **The artifact is not fetchable from outside**, and this is structural rather
than a matter of not having found the URL yet:

- **The official app contains no artifact URL and no Mender host.** The only occurrences of the
  string `mender` anywhere in `classes2.dex` are the Retrofit path `mender/deploy`. There is no
  `/api/v1/…`, no `zeus`, no `imx6`, no `.tar`, no `artifact`. The app is **purely a messenger** —
  it never sees the image, so it cannot have cached a URL for us to lift.
- **`ofpai.com/doppler/` hosts text only.** `latest.txt` and `release_notes-<build>.txt` both serve
  fine; `GET zeus_imx6ull_release-1714` and `…-1714.tar.gz` both 404, and the directory itself is
  403 (listing denied). No binaries are published there.
- **`mender.sandmandoppler.com` exists in DNS but is not reachable.** It resolves to `52.8.109.145`,
  which is a *different* AWS host from `control.sandmandoppler.com` (`13.56.26.209`, `52.8.12.101`)
  — so the name is real and not a wildcard. But **TCP 443 and TCP 80 both fail to connect**, and
  ICMP times out. Whatever runs there is not exposed to the public internet; it is presumably
  reachable only from the clock, or is firewalled to it.
- **`updates.` / `ota.` / `artifacts.sandmandoppler.com` are red herrings.** All three resolve to
  `34.174.60.199`, identical to the apex `sandmandoppler.com`, and all three return the same 403.
  That is one wildcard host serving the marketing site, not three artifact stores. Do not spend
  more time on them.

**Consequence: the only supported way to change the firmware is to ask the vendor backend to stage
a deployment.** Sideloading would mean obtaining the image from Palo Alto Innovation out-of-band
and writing a root filesystem to an i.MX6ULL with no documented recovery path. That is a
brick-the-clock risk with no rollback, and it is not worth it when the supported path is a single
authenticated POST.

### Release notes for 1714 (fetched, for the record)

`-Added restart timer to help crashes after 12 hours` / `-Improved weather service backoff logic` /
`-Fixed bug where colorsetup service could call its shutdown multiple times resulting in a crash` /
`-Fixed a bug causing blackout mode to go dark with time fade` /
`-Changed Name of Doppler smart buttons from Doppler Smart Button 1 to Doppler-12345678 Button 1` /
`-Fixed a bug in the restart logic` / `-Fixed some Alarm Bugs` /
`-Changed seconds display to have leading 0`

Worth noting that "restarts after 12 hours" and "colorsetup shutdown crash" are the kind of
long-horizon faults that would never show up in a short probe session — which is a reason to be
suspicious of any earlier conclusion that the clock is healthy on 1214.

### New endpoints the official app uses that this repo does not model

From the full Retrofit surface: `mender/deploy`, `hardware/update` (confirmed live, 200,
`{"updateInfo":""}` on this DSN), `software/use-rainbow-display`,
`software/snooze-length`, `software/use-snooze-display`, `alexa/ascending`, `alexa/challenge`,
`alexa/key`, `hardware/button1`, `hardware/button2`, `hardware/button-last-message`,
`hardware/sync-button-display-brightness`, `hardware/sync-button-display-color`, `is-setup`.

`software/snooze-length` (1347), `software/use-snooze-display` (1556), `alexa/ascending`,
`hardware/button1` / `button2` (1629) are all above 1214 as well, so they are gated out on this
clock too — consistent with `getAlarmsAscending`, `getDisplaySnooze` and `getSnoozeLength` being
present in the app but unusable here.

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
10. **CONFIRMED LIVE BUG — the seed set has disabled a working feature.**
   `software/use-leading-zero` is in `MEASURED_UNAVAILABLE` (`api/EndpointCapabilities.kt:88`) and
   therefore never polled, yet it answers `200 {"on":false}` on **4 of 6** attempts.
   `hardware/button-last-message` is not modelled at all and also answers 3/6. Both were classified
   absent from 2/2 probes. **Fixing this is the whole of open issue 2 and it is no longer
   theoretical.** The signature to recognise: a success takes ~3–5 s, a failure costs ~15 s — these
   handlers are slow, not missing. **Withdrawn:** the earlier suggestion here to gate on
   `softwareVersion` is wrong; `SNOOZE_LENGTH`=1347 is absent while `TIME_OFFSET`=1348 works, so
   the constants track app releases, not firmware capability. **Do not widen or delete the seed set
   wholesale — remove only the paths measured working, and make the learned path recoverable.**
   `LOCAL_CONTROL` (1544) being unusable may still explain the dead LAN, but that is untested.
11. **Three probe targets were invented endpoints.** `hardware/display-text`,
   `hardware/small-display-digits` and `hardware/display-dots` are in neither the official app's
   Retrofit surface nor `doppyler`'s client. The lightbar is `software/use-rainbow-display`.
   The `probeOverrideSupport` verdicts currently cached against those three paths are measuring
   nothing; consider retargeting at `software/use-rainbow-display` and dropping the rest.
12. **Firmware upgrade is impossible, not merely unattempted — Mender is shut down.** The vendor's
   CEO confirmed this directly (Oct 2026). `POST mender/deploy` may still 2xx on 1214 firmware, but
   there is no server behind it, so the clock polls, finds nothing, and silently does nothing. The
   image is unobtainable through any known channel. **This clock is pinned at 1214 permanently**, so
   open issues 10 and 11 stop being open questions and become the work: treat the 15 gated features
   as *absent by design*, not broken, and hide or clearly mark the UI that depends on them. See
   *If the firmware is ever recovered* for what a safe manual flash would still require — a recovery
   path, the partition layout, and signature verification are all missing, and all three are blocking.


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

## Session Handoff — Oct 3, 2026 (afternoon)

### ✅ Completed This Session

1. **Alarm write path regression from c9e62e8 — FIXED**
   - Root cause: `addOrUpdateAlarm()` used `alarm.id in clockAlarmIds` to route create-vs-update, but `clockAlarmIds` was only populated by `refresh()`. If `addOrUpdateAlarm` was called without a recent refresh, the set was stale/empty, causing every alarm operation to route through `POST /alarms` (create-only) instead of `PUT /alarms/{id}` (update). This silently created duplicate alarms on the clock.
   - Fix: Added `if (clockAlarmIds.isEmpty()) refresh()` at the start of `addOrUpdateAlarm()` in `DopplerRepository.kt`. The bottom `refresh()` is retained to update device state after the write.
   - Verified: 6/6 `AlarmWriteProtocolTest` tests pass, full 174-test suite green.

2. **Dashboard clock showing UTC instead of local time — FIXED**
   - Root cause: Dashboard displayed `state?.currentUtcHour`/`currentUtcMin` directly (UTC time). The timezone label used `state?.timezone` which defaults to `"America/Los_Angeles"` and was never updated from the clock.
   - Fix: Convert UTC to local time using the clock's `clockTimezone` field via `java.time.ZoneId`. Changed timezone label to use `state?.clockTimezone`.

3. **Spanish translation — string resources created**
   - `values/strings.xml` — 200+ English strings, clean, no duplicates
   - `values-es/strings.xml` — 200+ natural Spanish translations
   - `DashboardScreen.kt`, `AlarmsScreen.kt`, `WeatherScreen.kt`, `MainActivity.kt` — converted to `stringResource()`
   - `SettingsScreen.kt`, `DiagnosticsScreen.kt`, `ColorSelector.kt` — not yet converted (see below)

4. **Lightbar function — CONFIRMED NOT WORKING**
   - Tested 6 payloads via cloud relay: static cyan, blink, pulse, comet, sweep, rainbow
   - 5 returned HTTP 200, rainbow returned HTTP 417
   - **Zero visual change on the 29-LED lightbar** — the relay accepts the request but the clock does not render it
   - This is the same pattern as `display-text` and `small-display-digits`: the route exists (200) but the firmware does not act on it
   - **Recommendation**: Mark lightbar as unavailable in UI, or remove the feature

5. **Firmware update investigation — CLOSED. Mender is offline and the image is gone.** The
   vendor's CEO confirmed the OTA service is shut down (Oct 2026), so the clock is pinned at
   firmware `1214` permanently. See **The Firmware Update Path** for the mechanism as it was,
   **Feature Minimum Software Versions** for the consequence (15 of 16 official features do not
   exist on this build), and open issues 10–12 for what to do about it. The practical follow-up is
   to treat those gated features as *absent by design* and stop presenting them as broken.

### ⏭️ Not Yet Done

- **SettingsScreen.kt** — has `stringResource()` calls inside `onClick` lambdas (non-composable context). Need to extract strings before the lambda.
- **DiagnosticsScreen.kt** — type mismatch issues with `Long?` vs `Any` in string formatting.
- **ColorSelector.kt** — has `stringResource()` in property initializer (`PRESET_SWATCHES` list). Need to convert to a function.
- ~~**Firmware update mechanism**~~ — **done**, see *The Firmware Update Path*.

### Key Files Modified

| File | Change |
|------|--------|
| `repository/DopplerRepository.kt` | Added `if (clockAlarmIds.isEmpty()) refresh()` in `addOrUpdateAlarm()` |
| `ui/screens/DashboardScreen.kt` | UTC→local time conversion, `clockTimezone` for label, language toggle |
| `ui/screens/AlarmsScreen.kt` | Converted to `stringResource()` |
| `ui/screens/WeatherScreen.kt` | Converted to `stringResource()` |
| `MainActivity.kt` | Navigation bar uses `stringResource()` |
| `res/values/strings.xml` | 200+ English strings |
| `res/values-es/strings.xml` | 200+ Spanish translations |
| `.gitignore` | Added `sandman.xapk` |

### Build Status
- `compileDebugKotlin`: **BUILD SUCCESSFUL**
- Only deprecation warnings (non-blocking)

---

## Internationalization (i18n)

**Always implement strings in both English and Spanish on their corresponding files.**

The app supports two locales:
- `android/app/src/main/res/values/strings.xml` — English (default)
- `android/app/src/main/res/values-es/strings.xml` — Spanish

**Rules:**
1. Every user-facing string must exist in **both** files with the **same** `name` attribute.
2. Spanish translations must be **natural**, not literal — e.g. `dashboard` → `Panel`, not `Tablero`.
3. Use `stringResource(R.string.xxx)` in Compose UI — never hardcode user-facing text.
4. `stringResource()` can **only** be called from within a `@Composable` function. Do not call it in:
   - Property initializers (e.g. `private val X = listOf(stringResource(...))`)
   - `onClick` lambdas (extract the string outside the lambda first)
   - `companion object` blocks
5. For strings used inside `onClick` lambdas, extract them before the lambda:
   ```kotlin
   val label = stringResource(R.string.save)  // composable context
   Button(onClick = { doSomething(label) }) { Text(label) }
   ```
6. Language toggle is in `DashboardScreen.kt` — an EN/ES button that calls `recreate()`.

**Status (as of Oct 2026):**
- ✅ `DashboardScreen.kt` — uses `stringResource()` + language toggle
- ✅ `AlarmsScreen.kt` — uses `stringResource()` throughout
- ✅ `WeatherScreen.kt` — uses `stringResource()` throughout
- ✅ `MainActivity.kt` — navigation bar uses `stringResource()`
- ⏭️ `SettingsScreen.kt` — not yet converted (has `stringResource()` in `onClick` lambdas)
- ⏭️ `DiagnosticsScreen.kt` — not yet converted (type mismatch issues)
- ⏭️ `ColorSelector.kt` — not yet converted (string resource in property initializer)
- ✅ `values/strings.xml` — 200+ English strings, clean
- ✅ `values-es/strings.xml` — 200+ Spanish translations, clean

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