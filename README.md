# android-playground

A monorepo for small, independent Android sample apps.

Each app lives under `apps/<app-name>/` as its **own self-contained Gradle project** (its own `settings.gradle.kts`, `gradlew`, and build config). Apps do not depend on one another and are built independently, each by its own GitHub Actions workflow under `.github/workflows/`.

## Layout

```
android-playground/
├── apps/
│   └── tap-counter/            # first app — independent Gradle project
│       ├── app/                #   Android application module
│       ├── gradle/wrapper/     #   Gradle wrapper (pinned version)
│       ├── gradlew             #   per-app build entry point
│       ├── settings.gradle.kts
│       └── build.gradle.kts
└── .github/workflows/
    └── tap-counter.yml         # builds ONLY tap-counter (path-filtered)
```

### Adding a new app

1. Create `apps/<new-app>/` as its own Gradle project (copy tap-counter's structure).
2. Add `.github/workflows/<new-app>.yml` with a `paths:` filter scoped to `apps/<new-app>/**` and `working-directory: apps/<new-app>`.
3. Use its own tag prefix for releases, e.g. `newapp-v*`.

This keeps every app's source, build, CI, and release versioning fully independent.

## Apps

| App | Description |
|-----|-------------|
| [`tap-counter`](apps/tap-counter) | A button that increments a counter on each tap; overflows 100 → 1. |
| [`cf-scanner`](apps/cf-scanner) | Scans Cloudflare IP ranges and ranks genuinely usable "clean" IPs, tuned for restrictive networks. |
| [`sensor-monitor`](apps/sensor-monitor) | Live raw readings from the accelerometer, gyroscope and ambient temperature sensor. |

## Releases

Each app is versioned and released independently using a tag prefix:

- `tap-counter-v0.0.1`, `tap-counter-v0.0.2`, …
- `cf-scanner-v0.0.1`, `cf-scanner-v0.0.2`, …
- `sensor-monitor-v0.1.0`, `sensor-monitor-v0.1.1`, …

Pushing such a tag triggers that app's workflow, which builds the APK and attaches it to a GitHub Release.

### Versioning policy

[Semantic Versioning](https://semver.org), applied per app. The bump is decided from what the change *is*, not from how significant it feels:

| Bump | When |
|------|------|
| **MAJOR** | Breaks how the app is used, or requires action from existing users (e.g. a signing-key change, which forces an uninstall) |
| **MINOR** | Adds a user-visible capability, backward compatible |
| **PATCH** | Bug fixes, wording, styling, performance, refactors — no new capability |

`versionCode` increments by one on every release regardless, since Android requires it to be monotonic.

A release that both fixes bugs and adds a capability takes the highest applicable bump — so a mostly-fixes release that also adds one new feature is a MINOR, not a PATCH.
