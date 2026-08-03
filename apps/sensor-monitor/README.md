# sensor-monitor

Shows the raw output of the phone's accelerometer, gyroscope and ambient temperature sensor, live.

**Raw means raw.** Values are rendered exactly as the platform reports them — no smoothing, no filtering, no unit conversion. Any of those would quietly misrepresent what the hardware actually measured, which defeats the purpose.

## What it reads

| Sensor | Android type | Unit | Axes |
|--------|--------------|------|------|
| Accelerometer | `TYPE_ACCELEROMETER` | m/s² | X, Y, Z |
| Gyroscope | `TYPE_GYROSCOPE` | rad/s | X, Y, Z |
| Ambient temperature | `TYPE_AMBIENT_TEMPERATURE` | °C | single value |

A lying-still phone reads about **9.81 m/s²** on one accelerometer axis — that is gravity, not a fault. The gyroscope hovers near but rarely exactly zero; that residue is the sensor's own noise, and hiding it would be filtering.

### Missing sensors are stated, not blanked

**Most phones have no ambient temperature sensor.** Rather than leaving an empty row that could mean broken, warming up, or absent, each sensor has three explicit states:

- `Unavailable` — the device has no such sensor, said plainly in the card
- `WaitingForFirstReading` — the sensor exists but has not reported yet
- `Live` — a current reading

The manifest declares all three sensors `required="false"`, so the app installs on devices lacking any of them instead of being filtered out.

## UI

Material 3, light and dark, full RTL, Vazirmatn throughout.

- One card per sensor: title, unit, per-axis values, and magnitude for vector sensors
- **Magnitude** is the vector length. For the accelerometer at rest it stays near 9.81 regardless of how the phone is held — a unit test asserts exactly that
- A live/paused dot and a **pause** button, for reading an exact value without chasing changing digits
- **About** explains what each sensor measures and why gravity shows up at rest

### Why the readings are monospace

These numbers change many times a second. In a proportional face, every digit change nudges its neighbours and the row shimmers. Fixed-width digits hold position, so only the values move.

For the same reason precision is **fixed at three decimals** rather than adaptive: a digit count that varies with magnitude makes the row jitter, which is harder to read than a trailing zero.

### Persian numerals and bidi

Readings render in Persian digits with `٫` as the separator, and units stay Latin (`m/s²`) since that is how they are universally written.

Every value is wrapped in Unicode directional isolates. Without them the bidirectional algorithm detaches a minus sign from its number in an RTL row, so `−9.81` reads as `9.81−`. The app also uses a true minus (U+2212) rather than a hyphen, and suppresses signed zero: a tiny negative reading rounds to `0.000`, and `−۰٫۰۰۰` looks like a bug rather than a measurement. Sensors sit near zero constantly, so this comes up often.

## Update rate

Sensors are registered at `SENSOR_DELAY_UI`, not the fastest rate. At `SENSOR_DELAY_FASTEST` the accelerometer can fire hundreds of times a second — far past what a screen shows or an eye follows — and the surplus costs battery for no visible gain.

The UI is refreshed on a **fixed 100 ms cadence** from the newest stored reading, rather than binding on every callback. Redrawing per callback would burn main-thread time on frames nobody can read.

Sensors are unregistered in `onPause`, so nothing streams in the background.

## Build

Self-contained Gradle project. From this directory:

```bash
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # formatting + sensor state tests
```

## Signing

Signed with a stable key so updates install over previous versions instead of failing with `App not installed as package conflicts with an existing package`. Resolved from `SM_KEYSTORE_BASE64` (repository secret) or the checked-in `keystore/`, and `verifySigningConfigured` fails the build if neither is present.

## CI / Releases

Built by [`.github/workflows/sensor-monitor.yml`](../../.github/workflows/sensor-monitor.yml):

- Every push/PR touching `apps/sensor-monitor/**` runs the tests, builds the APK, and uploads it as an artifact
- Pushing a tag `sensor-monitor-v<version>` publishes the APK as a GitHub Release asset

Version comes from `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts). Current: **0.1.0**.

## Details

| | |
|--|--|
| Language | Kotlin |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 |
| Permissions | None — motion sensors need no runtime permission |
