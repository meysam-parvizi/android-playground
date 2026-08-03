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
| 6. **Payload transfer** | It can actually move bytes, not merely open a connection |

Stage 4 is the one that matters most, and the one latency-based scanners skip. An IP that fails it is marked unhealthy no matter how fast it looked.

Stage 6 closes a separate gap: an IP can pass every handshake and still stall the instant real data flows. Only moving bytes reveals that, so a transfer that returns nothing marks the IP unhealthy.

SNI is rotated across a pool of Cloudflare hostnames so DPI cannot fingerprint a single domain.

### Reading Cloudflare's own telemetry

Cloudflare's speed endpoints return `Server-Timing` headers that expose the server's view of the connection:

```
Server-Timing: cfSpeedEdge;dur=7, cfSpeedWorker;dur=41
server-timing: cfL4;desc="?proto=TCP&rtt=4024&min_rtt=3979&rtt_var=1524
                &lost=0&retrans=0&delivery_rate=1091731&cwnd=53"
```

Two things are taken from it:

- **`cfSpeedEdge;dur`** is subtracted from the measured latency, so the figure is network round-trip rather than round-trip plus however long the edge spent working.
- **`cfL4`** carries segment-level `lost` and `retrans` counts plus `rtt_var`, straight from the TCP stack that served the request. This matters because client-side loss is coarse: across three attempts it can only be 0%, 33%, 66% or 100%. An IP that completed every attempt while retransmitting heavily used to score as flawless; now those events add a capped penalty, so server telemetry can sharpen the picture without ever, on its own, condemning an otherwise working IP.

The headers are undocumented, so every field is optional and the parser is deliberately lenient — malformed or absent values are skipped and the scan behaves exactly as before. `ServerTimingParserTest` pins this against real captured headers and hostile input.

Adapted after reading [MortezaBashsiz/CFScanner](https://github.com/MortezaBashsiz/CFScanner), which uses `cfSpeedEdge` for latency correction; the `cfL4` loss and jitter data goes further than that project reads.

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
- **Weighted sampling** — 70% of candidates drawn from ranges that historically behave better from Iran, 30% from the full list so unusual-but-good edges still surface. Within a pool, ranges are picked uniformly by default; `sizeWeightedSampling` switches to picking in proportion to how many addresses each holds. Cloudflare's blocks differ in size by 512x (`104.16.0.0/13` holds 524,288 addresses, `131.0.72.0/22` holds 1,024), so uniform selection puts far more pressure on each address of a small block — a defensible heuristic, but previously an accidental one rather than a choice. Both modes are tested.
- **Neighbour expansion** — Cloudflare edges cluster, so a hit triggers probing of adjacent addresses (cheap, high yield). Capped at 25% of the requested scan size so a healthy network cannot extend the scan indefinitely, and the progress total grows with the extra work rather than staying fixed — otherwise a scan reports nonsense like "checking 334 of 300".

## UI

Material 3, with light and dark themes and full RTL layout. One screen, everything reachable without navigation:

- **Status card** — current state, live progress bar, and a green badge counting healthy finds
- **Settings card** — how many addresses to test, sort criterion, and a **restricted-network** switch carrying a one-line explanation of what it changes
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
- **About** in the toolbar explains the scoring model and what each number means

Controls that would corrupt an in-flight run are disabled while scanning; sort stays live so results can be re-ranked as they arrive.

### Making the numbers self-explanatory

The counts used to be ambiguous. Asking for 100 addresses and being told "125 tested · 61 healthy" raises two questions the UI never answered: what happened to the other 64, and why 125 rather than 100.

- The field is labelled **"how many IPs should be tested?"** with helper text saying they are drawn at random from Cloudflare's ranges — so it cannot be misread as "how many healthy ones to find".
- The summary spells out the split: **"125 tested: 61 healthy, 64 unhealthy"**, rather than leaving the user to subtract.
- When neighbour expansion tests extra addresses, a note explains it: **"25 extra: addresses next to each healthy IP were also tested"**.

`HeaderStateTest` asserts both sums close — `healthy + unhealthy == tested` and `requested + expansion == tested` — so a displayed breakdown can never contradict itself.

### VPN warning

Before the first scan of a session the app warns that a VPN, proxy, or custom DNS must be switched off.

This is about correctness, not etiquette: with a tunnel active every probe travels through it, so the measured latency and stability describe the tunnel rather than the network the results will be used on. A scan run over a VPN can rank an IP as excellent that is unusable once the VPN is off.

`VpnDetector` checks the active network for `TRANSPORT_VPN`. Detection is best-effort — some tunnels are indistinguishable at that level — so it is used to *strengthen* a warning that appears regardless, never to block a scan. The warning is shown once per launch, but reappears whenever a VPN is actually detected.

### Typography

The UI uses **[Vazirmatn](https://github.com/rastikerdar/vazirmatn) v33.003**, the standard free font for Persian interfaces. Three weights (regular/medium/bold) are bundled, ~370 KB total.

It is applied by overriding the Material 3 type scale in the theme rather than setting `fontFamily` per view, so every component picks it up — including ones the app never touches directly, such as dialogs, dropdown menus, and snackbars. The dark theme inherits a shared `Theme.CfScanner.Base`, so the font and type scale are declared once.

IP addresses stay **monospace**: aligned digits make a column of addresses much easier to compare, and the address is Latin-only so no Persian glyphs are involved.

## Languages

Persian and English, chosen with the globe button in the toolbar. **Persian is the default** — the app exists for users on restricted networks, so following a phone that ships set to English would be wrong more often than not.

The choice is stored and survives restart. AppCompat persists its own selection from API 33 onward but not below it, so the app keeps its own record and restores it in `Application.onCreate` — before any view inflates, so the first frame is already in the right language.

### Adding a language

Two steps, and no logic changes anywhere:

1. Add an entry to `LocaleRegistry.SUPPORTED`:

   ```kotlin
   AppLocale(tag = "ar", endonym = "العربية", usesPersianDigits = true)
   ```

2. Create `res/values-ar/strings.xml` with the same keys as the other languages, and add `<locale android:name="ar" />` to `res/xml/locales_config.xml`.

The picker, persistence, layout direction and number formatting are all derived from that list. `StringResourceParityTest` fails the build if a declared language is missing keys, declares stray ones, leaves a value blank, or disagrees on format placeholders — so a half-translated language cannot ship and silently fall back to Persian halfway down a screen. `LocaleConfigTest` fails if `locales_config.xml` and the registry drift apart.

Language names are shown as **endonyms**, each in its own script, because someone who cannot read the current interface language still needs to find theirs.

`usesPersianDigits` is declared per language rather than inferred from writing direction: the two do not track each other, since Arabic and Persian are both right-to-left but use different digit forms.

### Making the language actually apply

Three separate things had to be right. Getting only some of them produced an app that looked correct and behaved wrongly, and — because the failures were device-dependent — one that worked on one phone and not another.

**1. The manifest service.** `AppCompatDelegate.setApplicationLocales` is silently dropped on Android 12 and below unless this is declared:

```xml
<service
    android:name="androidx.appcompat.app.AppLocalesMetadataHolderService"
    android:enabled="false"
    android:exported="false">
    <meta-data android:name="autoStoreLocales" android:value="true" />
</service>
```

**2. Applying the default on first launch.** With nothing stored, AppCompat leaves the app on the *device* locale, so the picker showed the app's default while the resources followed the phone's.

**3. Not trusting `setApplicationLocales` at all.** The decisive one. On a Samsung device whose system does not list Persian, the call was accepted and then ignored; the same build was correct on a Xiaomi. The result was English text in a left-to-right layout **with Persian numerals** — the formatter read the requested language while the resources had resolved the device's.

`LocaleContext.wrap` rewrites the `Configuration` in `attachBaseContext`, on both the Application and the Activity, which no vendor build can override. The Activity matters most: each one is created with its own configuration, so wrapping only the Application still leaves it on the device language.

Two consequences worth keeping:

- Anything that must agree with the visible text reads `LocaleContext.effectiveLocale` — the locale the resources *actually* resolved to — never the stored preference. `LocaleRegistry.preferred` is the intent; the two disagreeing is exactly what the Persian-digits-in-English-UI bug was.
- A language switch calls `recreate()` explicitly, because relying on `setApplicationLocales` to restart the activity does nothing on a device that ignores it.

`LocaleWiringTest` and `LocaleOverrideTest` assert all of this. None of it is visible from reading the Kotlin, which is why it needed pinning.

### Every language needs its own `values-<tag>/` folder

Worth knowing before adding a language, because it produced a confusing bug: the app opened in **English** on first launch even though the picker showed Persian.

Persian was in the unqualified `res/values/` folder with no `values-fa/`. That folder is treated as *language-neutral*, and Android consults it only after **every** qualified folder has failed to match. AppCompat resolves against a locale *chain* — the app's choice followed by the device's locales — so on an English phone the chain was `[fa, en]`, `values-en/` matched `en`, and English won before `values/` was ever reached.

The fix is a real `values-fa/` folder, so `fa` matches directly and wins first. The layout is now:

| Folder | Contents | Role |
|--------|----------|------|
| `values-fa/` | Persian | the default language |
| `values-en/` | English | |
| `values/` | English | the fallback Android requires, for locales the app does not list |

Note that `android:localeConfig` does **not** fix this. The system reads it only from API 33 onward, and resource resolution ignores it entirely; it exists here so Android's own per-app language picker in Settings offers the right languages. Its `android:defaultLocale` attribute is deliberately omitted — it requires `compileSdk 35` and this app targets 34, so including it fails resource linking. `LocaleConfigTest` asserts that the default language has its own qualified folder, which is the property that actually prevents a regression.

### Persian numerals and bidi

Layout direction is `locale`, so it mirrors for Persian and stays left-to-right for English. It was previously forced to `rtl` because the UI was Persian-only; now that the app sets its own locale, forcing it would render the English UI right-aligned.

Measurements render in the digit shape of the selected language — `۸۵` in Persian, `85` in English — but **IP addresses always keep Latin digits, in every language**, because they get copied into client configs where Persian numerals would be useless.

Durations are shown as bare numerals with no `ms` suffix: a Latin unit beside Persian digits reads badly in a right-to-left row, and the chip label already establishes what the value is.

Latin words and numeric values embedded in Persian sentences are wrapped in Unicode directional isolates (`U+2068` / `U+2069`). Without them the bidirectional algorithm reorders the run: "loss 0%" rendered as `0%لاس`, and the settings hint scrambled around the word `WebSocket`. The isolates are applied in both languages — invisible in a left-to-right layout, and one code path rather than two. `LocaleAwareFormatTest` pins these rules, including that an address never contains a Persian digit in any language.

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

1. **Repository secrets** — `CFS_KEYSTORE_BASE64` (the keystore, base64-encoded) plus `CFS_KEYSTORE_PASSWORD`, `CFS_KEY_ALIAS`, and `CFS_KEY_PASSWORD`. CI passes these as environment variables and the build decodes the keystore into the build directory, never the source tree. This is the path CI uses.
2. **`keystore/cf-scanner-release.jks`** — checked in, used when no secret is present, so forks and plain clones still produce installable, consistently signed builds with no setup.

Both currently hold the *same* key, so the signature is identical either way and the fallback cannot break update installs. `./gradlew :app:verifySigningConfigured` runs in CI ahead of the build, logs which source was used, and fails loudly if neither is present.

> **Note:** versions up to 0.0.8 were each signed with a different throwaway key. Upgrading from one of those requires uninstalling first — one last time. From 0.0.9 onward, updates install cleanly.

### Rotating or replacing the key

To move to a key that is *only* in secrets, generate one and upload it:

```bash
keytool -genkeypair \
  -keystore my-release.jks -storetype PKCS12 \
  -storepass '<store-pass>' -keypass '<key-pass>' \
  -alias my-alias -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=CF Scanner, O=<you>, C=<cc>"

# Upload (requires the gh CLI, authenticated with repo scope)
base64 -w0 my-release.jks | gh secret set CFS_KEYSTORE_BASE64 --repo <owner>/<repo>
printf '<store-pass>' | gh secret set CFS_KEYSTORE_PASSWORD --repo <owner>/<repo>
printf 'my-alias'     | gh secret set CFS_KEY_ALIAS         --repo <owner>/<repo>
printf '<key-pass>'   | gh secret set CFS_KEY_PASSWORD      --repo <owner>/<repo>
```

Or through the web UI: **repository → Settings → Secrets and variables → Actions → New repository secret**, once per name above. For `CFS_KEYSTORE_BASE64`, paste the output of `base64 -w0 my-release.jks` as a single line.

Then delete `keystore/cf-scanner-release.jks` so the fallback cannot silently mask a misconfigured secret.

**Changing the key changes the signature**, so every existing install will need to be removed once more. Keep a backup of whichever keystore you settle on: lose it and you can never ship an update to installed copies.

## Versioning

[Semantic Versioning](https://semver.org), decided from the change itself rather than from how significant it feels:

| Bump | When | Example from this app |
|------|------|----------------------|
| **MAJOR** `1.0.0` | A change that breaks how the app is used, or requires action from existing users | Changing the signing key (forces an uninstall) |
| **MINOR** `0.x.0` | A new user-visible capability, backward compatible | The VPN warning dialog; the Copy button |
| **PATCH** `0.0.x` | Bug fixes, wording, styling, performance, refactors — no new capability | The scroll-stutter fix; clearer count labels; the Vazirmatn font |

`versionCode` increments by one on every release regardless, since Android requires it to be monotonic.

While the version is `0.y.z` the app is pre-1.0 and the surface may still shift; MINOR carries the weight MAJOR would after 1.0.

### Honest note on history

Versions `0.0.1`–`0.0.8` do not follow this policy. Everything was filed as a patch, including the full Material 3 redesign in `0.0.4`, which added user-visible capability and should have been a MINOR. The rule above was written down after that inconsistency was pointed out; releases from `0.2.0` onward follow it.

`0.2.0` itself is a borderline call: it was mostly clarification work, which is PATCH, but it also added the VPN warning, which is a new capability — so MINOR is defensible. Had it been only the wording changes, it should have been `0.1.1`.

## CI / Releases

Built by [`.github/workflows/cf-scanner.yml`](../../.github/workflows/cf-scanner.yml):

- Every push/PR touching `apps/cf-scanner/**` runs the unit tests, builds the APK, and uploads it as an artifact
- Pushing a tag `cf-scanner-v<version>` publishes the APK as a GitHub Release asset

Version comes from `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts). Current: **0.4.3**.

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
