# tap-counter

A deliberately tiny Android app: one button in the middle of the screen, with a big counter above it.

- Each **tap** increments the counter by **1**.
- When the counter is at **100**, the next tap **overflows back to 1**.
- The counter survives screen rotation.

## Build

This app is a self-contained Gradle project. From this directory:

```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## CI / Releases

Built by [`.github/workflows/tap-counter.yml`](../../.github/workflows/tap-counter.yml):

- **Every push / PR** touching `apps/tap-counter/**` builds a debug APK and uploads it as a workflow artifact.
- **Pushing a tag** `tap-counter-v<version>` builds the APK and publishes it as a GitHub Release asset named `tap-counter-v<version>.apk`.

Version is defined by `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts). Current: **0.0.1**.

## Details

| | |
|--|--|
| Language | Kotlin |
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 |
| Single screen | `MainActivity` + `activity_main.xml` |
