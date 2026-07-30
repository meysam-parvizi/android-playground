# cf-scanner

Scans Cloudflare's IP ranges and ranks the addresses that are actually usable, with the scoring tuned for restrictive networks such as Iranian ISPs.

Inspired by [SenPaiScanner](https://github.com/MatinSenPai/SenPaiScanner) (a Go TUI scanner), reimplemented for Android with a different — and stricter — ranking model.

## The problem this solves

Most "clean IP" scanners rank by latency. On a filtered network that is the wrong metric: an IP will happily complete a TCP connect, finish a TLS handshake, even answer `/cdn-cgi/trace` — and then get reset by DPI a few seconds later. A latency-only scanner reports that IP as excellent. It is useless.

So this scanner refuses to trust an IP until it has proven it can hold a connection.

## How an IP is tested

Each candidate goes through five stages per attempt, repeated `tries` times:

| Stage | What it proves |
|-------|----------------|
| 1. TCP connect | The address is routable; gives the latency sample |
| 2. TLS handshake (rotating SNI) | Encrypted transport is not blocked |
| 3. `GET /cdn-cgi/trace` | It is a **genuine Cloudflare edge**, and reveals the colo |
| 4. **Idle hold** | DPI does **not** reset the connection when it goes quiet |
| 5. WebSocket upgrade | Proxy protocols (VLESS/Trojan ride WS-over-TLS) can pass |

Stage 4 is the one that matters most, and the one latency-based scanners skip. An IP that fails it is marked unhealthy no matter how fast it looked.

SNI is rotated across a pool of Cloudflare hostnames so DPI cannot fingerprint a single domain.

## Ranking

A healthy IP gets a 0–100 score. Weights deliberately favour **consistency over raw speed**:

| Factor | Weight | Why |
|--------|--------|-----|
| Stability | 35% | Survived the idle hold (20) + WebSocket carry (15) |
| Packet loss | 25% | 0% = full marks, ≥20% = zero |
| Jitter | 20% | Std-dev of latency; ≤15 ms excellent, ≥200 ms zero |
| Latency | 15% | ≤60 ms excellent, ≥500 ms zero |
| Colo locality | 5% | Nearby datacenters (IST/FRA/AMS/DXB…) beat distant ones |

Thresholds are deliberately strict. An earlier, more generous calibration put every IP that cleared the health gate in the 90s, which made the grades meaningless — so the bands that are unusable in practice (>20% loss, >200 ms jitter, >500 ms latency) now score zero on that factor rather than merely low. A unit test asserts four quality tiers land in four different grades.

Consequence, and this is intentional: **a stable 250 ms IP (score 93) ranks above an unstable 30 ms one (score 0).** There is a unit test asserting exactly that.

Unhealthy results always sort below healthy ones, whichever sort criterion you pick — a fast broken IP is still broken.

## Improvements over the reference

- **Weighted multi-factor score** instead of sorting on one field
- **Two-stage idle hold** (~2.5 s in Iran mode) plus an urgent-data probe to surface a pending RST
- **Colo-distance penalty** — far colos are down-weighted for Iranian users
- **Real jitter** via standard deviation across attempts, not a single sample
- **Weighted sampling** — 70% of candidates drawn from ranges that historically behave better from Iran, 30% from the full list so unusual-but-good edges still surface
- **Neighbour expansion** — Cloudflare edges cluster, so a hit triggers probing of adjacent addresses (cheap, high yield)

## UI

Material 3, with light and dark themes and full RTL layout. One screen, everything reachable without navigation:

- **Status card** — current state, live progress bar, and a green badge counting healthy finds
- **Settings card** — dropdowns for scan size and sort criterion, plus a **restricted-network** switch carrying a one-line explanation of what it changes
- **Scan / Stop** — one large primary button that swaps label and icon with state
- **Results** — one card per IP: a rank badge tinted by grade, the address in monospace, the score with its grade, and compact metric chips (ping / jitter / loss / colo / WS). Loss is coloured when non-zero; the WS chip is highlighted since it signals real proxy-carry capability.
- **Empty state** — distinguishes "not scanned yet" from "scan finished, nothing found", each with a useful hint
- **Copy** puts a bare list of addresses on the clipboard — one IP per line, best first, nothing else:

  ```
  104.16.132.229
  172.64.80.1
  188.114.97.3
  ```

  No headers, comments, or metrics, so it can be pasted straight into a client config. `ResultExportTest` asserts the format.
- **Tap a single row** to copy just that address, for testing one IP quickly
- **About** in the toolbar explains the scoring model in plain language

Controls that would corrupt an in-flight run are disabled while scanning; sort stays live so results can be re-ranked as they arrive.

## Threading

The scan is entirely off the main thread, and this is load-bearing rather than incidental:

- Probing runs on `Dispatchers.IO`, bounded by a semaphore (default 16 concurrent).
- Every wait uses `delay`, never `Thread.sleep`. Blocking a dispatcher thread starves the pool, and `Thread.sleep` is not cancellable, so Stop would not work.
- `onProgress` / `onResult` are always dispatched on `Dispatchers.Main`, so the UI layer can touch views from them directly and safely.
- Progress callbacks are throttled (default 150 ms). Unthrottled, hundreds of completions per second flood the main thread and Android raises an ANR.
- Ranking runs on `Dispatchers.Default`, not in the callback.

`ScanEngineTest` asserts each of these — callback thread, throttling, prompt cancellation, and non-overlapping callbacks.

Version 0.0.2 fixes a freeze where starting a scan made the app unresponsive: callbacks mutated views from `Dispatchers.IO`, progress was unthrottled, `Thread.sleep` starved the IO pool, and the liveness check used `sendUrgentData`, which most Android devices reject — so no IP was ever reported healthy.

## Build

Self-contained Gradle project. From this directory:

```bash
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # ranking + CIDR logic tests
```

## CI / Releases

Built by [`.github/workflows/cf-scanner.yml`](../../.github/workflows/cf-scanner.yml):

- Every push/PR touching `apps/cf-scanner/**` runs the unit tests, builds the APK, and uploads it as an artifact
- Pushing a tag `cf-scanner-v<version>` publishes the APK as a GitHub Release asset

Version comes from `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts). Current: **0.0.4**.

## Details

| | |
|--|--|
| Language | Kotlin + coroutines |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE` |

## Notes and limitations

- Results are a **snapshot**. DPI behaviour shifts hourly; rescan when IPs stop working.
- Scanning is network-heavy. Prefer Wi‑Fi, and note that a large scan drains battery.
- Certificate validation is intentionally skipped **for probing only** — we connect to bare IPs, so the cert can never match the SNI. Edge identity is instead confirmed via `/cdn-cgi/trace`. This app carries no user traffic.
- Finding clean IPs is not itself a proxy. Feed the output into your own client config.
