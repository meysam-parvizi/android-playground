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
- **Neighbour expansion** — Cloudflare edges cluster, so a hit triggers probing of adjacent addresses (cheap, high yield). Capped at 25% of the requested scan size so a healthy network cannot extend the scan indefinitely, and the progress total grows with the extra work rather than staying fixed — otherwise a scan reports nonsense like "checking 334 of 300".

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

### Typography

The UI uses **[Vazirmatn](https://github.com/rastikerdar/vazirmatn) v33.003**, the standard free font for Persian interfaces. Three weights (regular/medium/bold) are bundled, ~370 KB total.

It is applied by overriding the Material 3 type scale in the theme rather than setting `fontFamily` per view, so every component picks it up — including ones the app never touches directly, such as dialogs, dropdown menus, and snackbars. The dark theme inherits a shared `Theme.CfScanner.Base`, so the font and type scale are declared once.

IP addresses stay **monospace**: aligned digits make a column of addresses much easier to compare, and the address is Latin-only so no Persian glyphs are involved.

### Persian numerals and bidi

The layout direction is **forced to RTL** rather than inherited from the device locale. Leaving it as `locale` kept the layout left-to-right on an English-locale device, which made the mixed Persian/Latin lines read as though only the words had been translated.

All measurements render in Persian digits (`۸۵`, `۲۹`, `۰٪`), but **IP addresses deliberately keep Latin digits** — they get copied into client configs, where Persian numerals would be useless.

Durations are shown as bare numerals with no `ms` suffix: a Latin unit beside Persian digits reads badly in a right-to-left row, and the chip label (`پینگ`, `نوسان`) already establishes what the value is.

Latin words and numeric values embedded in Persian sentences are wrapped in Unicode directional isolates (`U+2068` / `U+2069`). Without them the bidirectional algorithm reorders the run: "loss 0%" rendered as `0%لاس`, and the settings hint scrambled around the word `WebSocket`. `FormatTest` pins both rules, including that an address never contains a Persian digit.

### Keeping the list smooth

The whole screen is **one `RecyclerView`**: the controls, the placeholder, and the results are separate adapters joined with `ConcatAdapter`.

This matters for performance, not tidiness. The earlier version wrapped a `wrap_content` `RecyclerView` in a `NestedScrollView`, which measures the list unbounded — so it laid out every row at full height and **recycled nothing**. With a few hundred results, every update touched every row. Making the header a list row instead lets recycling work normally.

On top of that:

- Re-ranking is **debounced** (250 ms), collapsing a burst of hits into one sort plus one diff. Changing the sort criterion bypasses the debounce, since a delay there reads as lag.
- Updates go through **`DiffUtil`**, so only rows that actually moved or changed are rebound.
- Item animations are off and a small view cache is kept — ranks shift constantly during a scan, so animating every move is pure overhead.

### Structure

Rendering is separated from behaviour so the UI cannot contradict itself:

| Type | Responsibility |
|------|----------------|
| `MainActivity` | Owns the scan lifecycle and `HeaderState`; contains no rendering |
| `HeaderAdapter` | Renders the controls block, reports intent via a `Callbacks` interface |
| `EmptyStateAdapter` | Renders the placeholder row |
| `ResultAdapter` | Renders result rows, diffing on change |
| `ScanPhase` / `EmptyStateRules` | Pure, unit-tested rules for which placeholder to show |

`EmptyStateRules` is the single source of truth for the placeholder. That is what fixes the bug where "no healthy IP found" appeared above real results: with results present it returns no placeholder at all, in every phase, and "try again" is reachable only once a scan has actually finished empty — while a scan is running it says it is still searching.

### Responsive layout

`layout/item_header.xml` stacks the two dropdowns; `layout-w480dp/item_header.xml` places them side by side so the controls stay compact and more of the list is visible on wider screens and in landscape. Both variants define the same view IDs, so the adapter is unaware of which one is inflated. All paddings and margins use `start`/`end` rather than `left`/`right`, so the UI mirrors correctly.

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

## Signing

Every release is signed with a **stable key**, so a new version installs straight over the previous one.

This is not cosmetic. Android refuses to install an APK over one signed with a different key, failing with `App not installed as package conflicts with an existing package`. Debug builds are signed with a keystore Gradle generates on demand, and each CI run starts on a fresh machine — so every build used to get a brand-new key and every update had to be uninstalled first.

The key is resolved in this order:

1. **`CFS_KEYSTORE_BASE64`** environment variable (plus `CFS_KEYSTORE_PASSWORD`, `CFS_KEY_ALIAS`, `CFS_KEY_PASSWORD`), decoded into the build directory. This is how CI signs when the keystore is held in repository secrets — the key never enters the repository.
2. **`keystore/cf-scanner-release.jks`**, checked in, so a plain `git clone` produces installable, consistently signed builds with no setup.

The checked-in keystore is deliberately not a secret: it keeps the signature stable for a sample app, it does not prove authorship. Anything published to a store should use option 1.

`./gradlew :app:verifySigningConfigured` runs in CI ahead of the build and fails loudly if neither source is present, rather than silently shipping an APK that cannot be updated.

> **Note:** versions up to 0.0.8 were each signed with a different throwaway key. Upgrading from one of those to 0.0.9 still requires uninstalling first — one last time. From 0.0.9 onward, updates install cleanly.

## CI / Releases

Built by [`.github/workflows/cf-scanner.yml`](../../.github/workflows/cf-scanner.yml):

- Every push/PR touching `apps/cf-scanner/**` runs the unit tests, builds the APK, and uploads it as an artifact
- Pushing a tag `cf-scanner-v<version>` publishes the APK as a GitHub Release asset

Version comes from `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts). Current: **0.0.9**.

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
