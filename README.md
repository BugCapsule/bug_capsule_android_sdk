# BugCapsule Android SDK

BugCapsule records what led up to a bug in your Android app and saves it as a
portable `.bugcapsule` file. A developer can **replay** that file on an
emulator against the real app code, **verify** a fix against it, and keep it
as a **regression test**. The same SDK also reports errors (including ANRs and
native crashes), release health, performance traces and user feedback, and
links each error to the capsule that replays it.

```
QA hits a bug      ─► capsule captured, redacted on the device, uploaded
bugcapsule run     ─► REPRODUCED 3/3   (instrumentation test on an emulator)
fix the code
bugcapsule verify  ─► FIXED 3/3
bugcapsule regress ─► runs in CI from now on
```

Kotlin-first, with a Java-friendly API.

📖 Full documentation: **[docs.bugcapsule.com/android](https://docs.bugcapsule.com/android.html)**

## Contents

- [Artifacts](#artifacts)
- [Requirements](#requirements)
- [Install](#install)
- [Connect your app](#connect-your-app)
- [Make bugs replayable](#make-bugs-replayable)
- [Replay, verify and keep a regression test](#replay-verify-and-keep-a-regression-test)
- [Monitoring](#monitoring)
- [Privacy](#privacy)
- [Going to production](#going-to-production)
- [Limitations](#limitations)
- [Demo app](#demo-app)
- [More documentation](#more-documentation)
- [Support and license](#support-and-license)

## Artifacts

All artifacts are on Maven Central under the group `io.github.bugcapsule`.
Keep them on the same version.

| Artifact | What it is |
|---|---|
| `bugcapsule-android` | The SDK: capture, upload, replay engine, monitoring, Views helpers, SharedPreferences / DataStore checkpoints, ViewModel state |
| `bugcapsule-okhttp` | OkHttp (and Retrofit) capture and replay, OkHttp WebSockets, HTTP spans and trace propagation |
| `bugcapsule-navigation` | Jetpack Navigation routes, back-stack restore, navigation transactions |
| `bugcapsule-compose` | Compose helpers: actions, tracked text, navigation, screen tracing, feedback sheet |
| `bugcapsule-ndk` | Native (NDK) crash reporting (optional) |
| `bugcapsule-sqlite` | SQLite / Room query spans (optional) |
| `bugcapsule-android-replay` | **Test only**: the replay runner the CLI uses (androidTest), and `NativeAdapterConformance` for your adapters' unit tests |
| Gradle plugin `io.github.bugcapsule.gradle` | Uploads R8/ProGuard mappings so stack traces from minified builds are readable (optional) |

## Requirements

- A BugCapsule project. Sign in at [app.bugcapsule.com](https://app.bugcapsule.com).
- minSdk 23, compileSdk 34+, JDK 17.
- An emulator for replay (API 35 is the tested level).
- Preferences DataStore checkpoints need `androidx.datastore:datastore-preferences` 1.1+.

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.bugcapsule:bugcapsule-android:0.4.1-beta.6")
    implementation("io.github.bugcapsule:bugcapsule-okhttp:0.4.1-beta.6")
    implementation("io.github.bugcapsule:bugcapsule-navigation:0.4.1-beta.6")
    implementation("io.github.bugcapsule:bugcapsule-compose:0.4.1-beta.6")   // if you use Compose
    implementation("io.github.bugcapsule:bugcapsule-ndk:0.4.1-beta.6")       // optional: native crashes
    implementation("io.github.bugcapsule:bugcapsule-sqlite:0.4.1-beta.6")    // optional: SQLite / Room spans
    androidTestImplementation("io.github.bugcapsule:bugcapsule-android-replay:0.4.1-beta.6")
}
```

Make sure `mavenCentral()` is in your repositories. Java and Kotlin packages
are `dev.bugcapsule.*`.

> **Versions.** SDK tokens and connection status are available starting with `0.4.1-beta.2`.
> Existing explicit backend configuration remains supported.

## Connect your app

### 1. Get an SDK token

In the dashboard, open **Project settings → Keys → SDK tokens** and generate a
token. It looks like `bcsdk.v1.…` and is shown once, so copy it right away.

The SDK token is the only value your app needs. It holds your BugCapsule
address and a send-only credential: it can upload capsules and events and read
capture settings, nothing else. That makes it safe to ship in the APK. You can
rotate or revoke it in the dashboard at any time.

Pass it in as a `BuildConfig` field, so it comes from the environment or CI
secrets rather than your sources:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        buildConfigField("String", "BUGCAPSULE_TOKEN",
            "\"${providers.environmentVariable("BUGCAPSULE_SDK_TOKEN").getOrElse("")}\"")
    }
    buildFeatures.buildConfig = true
}
```

### 2. Initialize first in `Application.onCreate`

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BugCapsule.init(this, BuildConfig.BUGCAPSULE_TOKEN)
    }
}
```

```java
BugCapsule.init(this, BuildConfig.BUGCAPSULE_TOKEN);   // Java
```

That is a complete connection. Capture and monitoring (errors, ANRs,
sessions, screen tracing) start automatically, and the app version and build
come from the package info.

- **Call it first in `Application.onCreate`**, not from a ContentProvider or an
  App Startup initializer: replay restores storage right before
  `Application.onCreate`.
- **It never breaks your app.** `init` never throws and never blocks on the
  network. A token that can't be used logs one line under the `BugCapsule` tag
  saying what to fix, and the app runs without uploads.

### 3. Check the connection

```kotlin
val status = BugCapsule.status()
Log.i("Shop", "BugCapsule ${status.connection.state.code}: ${status.connection.message ?: "ok"}, " +
    "missing ${status.instrumentation.missing}")
```

`status.connection` has two separate confirmations: `configFetchedAt` (the app
reached BugCapsule with a valid token) and `firstEventAcceptedAt` /
`firstCapsuleAcceptedAt` (captured data arrived).

| `state` | Meaning | What to do |
|---|---|---|
| `not_configured` | No token and no backend: capsules stay on the device. | Pass a token. |
| `invalid_token` / `unsupported_token_version` | The token can't be used; `message` says why. | Paste the full `bcsdk.v1.…` token, or update the SDK. |
| `connecting` | The configuration hasn't arrived yet. | Wait a moment. |
| `connected` | BugCapsule answered the configuration request. | Nothing. |
| `offline` | No network, a timeout or a server error. Data waits in the outbox and is retried. | Check the device's network. |
| `rejected` | The token was refused (`reason`: `token_revoked`, `token_expired`, `token_unknown`, …). Capture and uploads stop for this app session. | Generate a new token. |
| `disabled` | `enabled(false)`, the project's kill switch, or a replay run. | — |

### Connect with an address and ingest key

For self-hosted setups, older SDK versions, or apps that already pass an
address explicitly:

```kotlin
BugCapsuleConfig.builder()
    .backend("https://api.bugcapsule.com", BuildConfig.BUGCAPSULE_INGEST_KEY)   // bct_… or bci_…
    .build()
```

The explicit backend wins when both are set (the SDK logs that the token was
ignored). The ingest key is public like the token. **Never put a `bcs_…` secret
key in an app.**

On an emulator, a BugCapsule server running on your own computer is
`10.0.2.2`, not `127.0.0.1`.

## Make bugs replayable

Being connected is not the same as faithful replay. Replay also needs your
app's network, navigation and state. Add the adapters that match your app;
`BugCapsule.status().instrumentation.missing` tells you what is left.

| Adapter | Gives replay |
|---|---|
| `BugCapsuleOkHttp` (`bugcapsule-okhttp`) | Network: OkHttp and Retrofit |
| `BugCapsuleWebSockets` (`bugcapsule-okhttp`) | OkHttp WebSockets opened through its factory |
| `NativeInteractions` + your `NativeAdapter` | A third-party SDK (location, auth, database, payments) |
| `BugCapsuleNavigation` (`bugcapsule-navigation`) | Routes and back-stack restore |
| `BugCapsuleSharedPreferences`, `BugCapsuleDataStore`, `registerState`, `registerViewModel` | App state in checkpoints |
| `bugcapsule-compose` helpers | Actions, tracked text, screens in Compose |

```kotlin
// app/build.gradle.kts: the replay runner and the build's commit
android {
    defaultConfig {
        testInstrumentationRunner = "dev.bugcapsule.android.replay.BugCapsuleReplayRunner"
        // The CLI passes -PbugcapsuleGitSha=<sha> so capsules and replays carry the build's commit.
        buildConfigField("String", "BUGCAPSULE_GIT_SHA",
            "\"${providers.gradleProperty("bugcapsuleGitSha").getOrElse("")}\"")
    }
}
```

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BugCapsule.init(
            this,
            BugCapsuleConfig.builder()
                .token(BuildConfig.BUGCAPSULE_TOKEN)
                .mode(CaptureMode.QA)                     // default: DEBUG if debuggable, else PRODUCTION
                .gitSha(BuildConfig.BUGCAPSULE_GIT_SHA)
                .redactKeys("loyaltyId")
                .build(),
            listOf(
                okHttp,
                navigation,
                BugCapsuleSharedPreferences.builder().allowFile("shop_prefs").build(),
                BugCapsuleDataStore.builder().allow("settings").build(),
            ),
        )
    }

    companion object {
        val okHttp = BugCapsuleOkHttp(rules = listOf(
            // Fields that change on every run must not affect request matching.
            EndpointRule("POST", "/api/checkout", ignoreBodyFields = setOf("timestamp", "nonce"))))
        val navigation = BugCapsuleNavigation()
    }
}

val client = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor())          // your interceptors first: they still run in replay
    .addInterceptor(ShopApp.okHttp.interceptor) // BugCapsule last: the innermost application interceptor
    .build()
```

### WebSockets and native SDKs

OkHttp WebSockets are captured when the app opens them through
`BugCapsuleWebSockets`. Replay never connects: recorded messages, closes and
failures reach your `WebSocketListener` at their recorded latency, and a
connection that was not recorded fails through `onFailure`.

```kotlin
val webSockets = BugCapsuleWebSockets()        // add it to BugCapsule.init's integrations
val sockets: WebSocket.Factory = webSockets.factory(client)
val ws = sockets.newWebSocket(Request.Builder().url("wss://quotes.example.com/v1/stream").build(), listener)
```

Binary frames are recorded as size only unless you pass
`BugCapsuleWebSockets(captureBinary = true)`; replay cannot serve them
otherwise. In replay, listener callbacks run on one background thread
(`BugCapsule-WebSocket`), in recorded order across all sockets.

Other SDKs need an adapter: implement `NativeAdapter` (identity, operations,
payload policy) and route the SDK's calls and subscriptions through
`NativeInteractions.call` / `subscribe` in a small wrapper. Capture records
them with the session's redaction and limits; replay answers from the
capsule without calling the SDK.

```kotlin
val location = NativeInteractions(LocationAdapter())   // add it to BugCapsule.init's integrations
val provider: LocationProvider = BugCapsuleLocationProvider(realProvider, location)

// In the wrapper:
bridge.call("location.last", null, callback) { cb -> real.lastLocation(cb) }
bridge.subscribe("location.updates", mapOf("intervalMs" to 1000), subscriber) { s -> real.requestUpdates(s) }
```

Check your adapter in a plain unit test with `NativeAdapterConformance`
(`testImplementation("io.github.bugcapsule:bugcapsule-android-replay:…")`): it
records a scripted session, replays it offline and fails on any divergence,
on SDK use during replay, and when an unsupported operation does not produce
an explicit gap. A complete example adapter and its test:
[`LocationAdapter.kt`](bugcapsule-android-replay/src/test/kotlin/dev/bugcapsule/android/replay/example/LocationAdapter.kt),
[`LocationAdapterConformanceTest.kt`](bugcapsule-android-replay/src/test/kotlin/dev/bugcapsule/android/replay/example/LocationAdapterConformanceTest.kt).

Capsules with WebSocket or adapter traffic use capsule format v2. Replaying
one needs the same adapter installed; otherwise the attempt ends
`incompatible` (`dependency.unsupported`). Your project's capture settings
can turn off a single adapter (for example `bugcapsule-okhttp.websocket`)
without affecting HTTP capture.

### Actions, text, assertions and state

```kotlin
// Views (password input types are always redacted)
BugCapsuleViews.onClick(checkoutButton, "cart.checkout") { viewModel.checkout() }
BugCapsuleViews.trackText(couponField, "cart.coupon")

// Compose
val coupon = rememberTrackedText("cart.coupon")
TextField(value = coupon.value, onValueChange = coupon::onValueChange)
val password = rememberTrackedText("login.password", sensitive = true)
Button(onClick = rememberBugCapsuleAction("cart.checkout") { viewModel.checkout() }) { Text("Checkout") }
BugCapsuleNavigationEffect(navController, ShopApp.navigation)

// Jetpack Navigation with Fragments: after the graph is set
ShopApp.navigation.attach(navHostFragment.navController)

// Say what "broken" means
BugCapsule.check("cart_total_non_negative", total >= 0, "total $total")
BugCapsule.fail("checkout_stuck", "spinner for 30 s")
try { … } catch (e: IllegalStateException) { BugCapsule.reportError(e) }

// State saved in checkpoints and restored in replay
BugCapsule.registerViewModel(this, "cart", { state.toJson() }) { value -> state = CartState.fromJson(value) }
BugCapsule.registerState("session", { sessionStore.snapshot() }) { sessionStore.restore(it) }

// Deterministic values: replay returns the recorded ones
val nonce = BugCapsule.random().nextInt()
val now = BugCapsule.currentTimeMillis()

// Manual capture, e.g. from a QA button
BugCapsule.capture(note = "QA: total looks wrong")
```

Java:

```java
BugCapsuleConfig config = BugCapsuleConfig.builder().token(BuildConfig.BUGCAPSULE_TOKEN).mode(CaptureMode.QA).build();
BugCapsule.init(app, config, Arrays.asList(okHttp, navigation));
BugCapsuleViews.onClick(button, "cart.checkout", v -> viewModel.checkout());
BugCapsule.check("cart_total_non_negative", total >= 0, "total " + total);
```

### What is captured

| Area | Captured |
|---|---|
| Lifecycle | Process-level resume, pause and stop (configuration changes ignored) |
| Touches | Every Activity's touch events, in dp. No accessibility service, no screen recording. |
| Back key | The system Back key |
| Actions and text | Only through the helpers above |
| Navigation | Jetpack Navigation destinations |
| Network | OkHttp exchanges, with a bounded body prefix (256 KiB text, 64 KiB binary) |
| WebSockets | OkHttp sockets opened through `BugCapsuleWebSockets`: connects, sends, messages, closes and failures (64 KiB per frame, binary as size only by default) |
| Native SDKs | Calls and subscriptions of SDKs you route through a `NativeAdapter` |
| Storage | Allowlisted SharedPreferences files/keys and DataStore stores |
| Failures | `check` / `fail`, `reportError`, and uncaught JVM exceptions on any thread |

Uncaught exceptions get a capsule written synchronously and uploaded on the
next launch; your previous crash handler always still runs. Native crashes,
ANRs and OOM kills get **no capsule**, but they are still reported by
[monitoring](#monitoring). Exact boundaries:
[docs/how-it-works.md](docs/how-it-works.md#failure-capture-boundaries).

## Replay, verify and keep a regression test

### Install the CLI

The standalone `bugcapsule` CLI replays capsules from every BugCapsule SDK.
Install it with Node.js 20 or newer; Dart and Flutter are not required.

```bash
npm install -g @bugcapsule/cli@0.4.0-beta.3
bugcapsule --version
```

Direct executables are also available from the [CLI release](https://github.com/BugCapsule/bug_capsule_flutter_sdk/releases/tag/v0.4.1-beta.7).

Log in with a personal token from **Project settings → Keys & CLI access**.
In CI, use a service token in `BUGCAPSULE_TOKEN` instead.

```bash
bugcapsule login --url https://api.bugcapsule.com --token bcp_…
```

### Configure replay

Add a `bugcapsule.json` at the project root:

```json
{
  "runtime": "android",
  "android": {
    "module": "app",
    "variant": "debug",
    "applicationId": "com.example.shop",
    "avd": "Pixel_6_2"
  }
}
```

Only `applicationId` is required. All keys:
[docs/how-it-works.md](docs/how-it-works.md#bugcapsulejson).

### Run it

```bash
bugcapsule list                      # capsules in your project
bugcapsule inspect <id>              # failure, eligibility, network, timeline
bugcapsule run <id> -n 3             # builds the capsule's commit and replays it on the emulator
bugcapsule verify <id> -n 3          # builds your working tree; FIXED when the bug is gone
bugcapsule regress add <id>          # keep it as a regression test
bugcapsule regress run               # in CI: exit 1 when a regression fails
```

The CLI builds the app and its androidTest APK, clears the app's data before
each attempt and drives it through an instrumentation test. If no device is
running it boots the `avd` headless. Unlock the device once after boot.

**Replay is off in production.** It can only be started from the
instrumentation runner, which ships in the androidTest APK only, and only for
a debuggable build. There is no intent, deep link, URL or push into replay.

See the [CLI reference](https://docs.bugcapsule.com/cli.html) and
[how verdicts work](https://docs.bugcapsule.com/verdicts.html).

## Monitoring

Monitoring runs in every session, whether or not it records a capsule:

| Signal | What you get |
|---|---|
| Errors | Uncaught exceptions, `reportError` / `check` / `fail`, `captureException` / `captureMessage`, **ANRs** (main thread blocked 5 s), **native crashes** with `bugcapsule-ndk` |
| How the last run ended (Android 11+) | ANRs the system killed the app for, native crashes (every thread from the tombstone, Android 12+), low-memory kills in the foreground: reported on the next launch from `ApplicationExitInfo` |
| Release health | One session per foreground run; `crashed` and `abnormal` (killed in the foreground) sessions, counted against the release that ran them |
| Performance | Activity and Fragment loads (TTID / TTFD), cold and warm start, Navigation and Compose screens, slow and frozen frames, OkHttp and SQLite spans |
| User feedback | `captureFeedback`, a Views dialog, a Compose sheet, shake to report |

When a capsule is written for an error, the error links to it, so each issue
lists the capsules that replay it.

**Crashes that kill the process are replayable too.** A recording session
keeps a crash journal on disk (written off the main thread, at most
`2 × maxCapsuleBytes`). When the process dies of a native crash, an ANR kill
or a foreground low-memory kill, the next launch builds a crash capsule from
that journal and links it to the crash's event. Replay reports `reproduced`
when the app dies the same way again. Details:
[docs/how-it-works.md](docs/how-it-works.md#crash-capsules).

Crashes and ANRs list every thread, main thread first. A crash reported on the
next launch (native crash, ANR kill) carries the breadcrumbs, user, tags and
release of the process that crashed, not of the launch that reports it.
`enableExitInfoTracking(false)` turns the exit records off.

```kotlin
BugCapsuleConfig.builder()
    .token(BuildConfig.BUGCAPSULE_TOKEN)
    .tracesSampleRate(0.2)
    .tracePropagationTargets("api.shop.com")       // none by default
    .beforeSend { event, hint -> if (hint.throwable is CancellationException) null else event }
    .enableShakeToReport(true)
    .build()

BugCapsule.setUser(User(id = "u_123"))
BugCapsule.captureException(e)
BugCapsuleFeedbackDialog.show(activity)
```

`monitoring(false)` turns monitoring off; capsules keep working. All options
and the full API: [docs/how-it-works.md](docs/how-it-works.md#monitoring).

### Readable stack traces from minified builds

The Gradle plugin embeds an id in every minified build and uploads its
`mapping.txt`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

// app/build.gradle.kts
plugins { id("io.github.bugcapsule.gradle") version "0.4.1-beta.6" }

bugcapsule {
    url.set("https://api.bugcapsule.com")   // or -PbugcapsuleUrl / BUGCAPSULE_URL
    uploadProguardMapping.set(true)
    failOnUploadError.set(true)
}
```

Set `BUGCAPSULE_SECRET_KEY` (a `bcs_…` key) in your CI secrets, never in the
app. Without a URL or key the id is still embedded and the upload is skipped;
`./gradlew uploadBugCapsuleProguardMapping<Variant>` uploads by hand.

The plugin also uploads the unstripped native libraries (`.so` files with a
build id and symbols, your own and those of your dependencies) of every
release (non-debuggable) variant, so native crash frames resolve to functions
and lines.
`uploadNativeSymbols.set(false)` turns it off;
`./gradlew uploadBugCapsuleNativeSymbols<Variant>` uploads by hand. More in
[Release symbols](https://docs.bugcapsule.com/symbols.html).

## Privacy

Redaction runs on the device before anything is written: sensitive keys in
bodies, storage, state and payloads; sensitive headers; credentials in URLs;
and tokens, JWTs, API keys and card numbers in free text.

Tighten it with `redactKeys`, `redactHeaders`, `redactPattern(...)`,
`beforeRecord` / `beforeCheckpoint` and `BugCapsuleOkHttp(exclude = …)`.
Storage is captured only when you allowlist it, and password input types are
always redacted. Your project settings can also add redaction keys, turn off
body capture, lower sampling or switch capture off remotely.

Redaction is best effort. It does **not** find secrets in URL path segments,
personal data that isn't a credential, or secrets in binary bodies. Inspect
real capsules with `bugcapsule inspect` before a wider rollout. More:
[Privacy and redaction](https://docs.bugcapsule.com/privacy.html).

## Going to production

- Production sessions record a sample of sessions: `productionSampleRate`
  (default `0.1`), capped by your project's rate. Monitoring is not sampled.
- Learn the kill switch before you need it: `bugcapsule capture --on` stops
  capture for every device and deletes queued capsules; `--off` resumes it.
- Upload the R8 mapping for every minified release.
- Allow cleartext traffic only for local hosts.
- Read the [production checklist](https://docs.bugcapsule.com/production.html).

Data waits in a bounded outbox (10 capsules / 10 MiB / 7 days) and is retried
with backoff. Storage and network errors never reach your app. Details:
[docs/how-it-works.md](docs/how-it-works.md#storage-and-uploads).

## Limitations

- Replay runs through the instrumentation runner on an emulator or device with
  a debuggable build. Pointer replay works best on the same screen size
  (otherwise coordinates are scaled).
- Only the Back key is recorded among hardware keys; keyboard text arrives
  through tracked fields only.
- OkHttp replay answers inside the interceptor: interceptors added after it,
  network interceptors, caching and cookies are not exercised in replay.
- Time and randomness are deterministic only through
  `BugCapsule.currentTimeMillis()` and `BugCapsule.random()`. WorkManager,
  alarms and Bluetooth are not captured. Other network stacks
  (HttpURLConnection, Ktor, Cronet, WebSockets not opened through
  `BugCapsuleWebSockets`) and SDKs such as location, maps or Firebase need an
  adapter (`NativeAdapter`); without one they are not captured.
- WebSocket and adapter replay cannot restore connections or subscriptions
  that were open at a periodic checkpoint; such capsules are marked
  ineligible and replay reports `incompatible`.
- The NDK crash handler is not yet verified on physical devices.
- Crash capsules miss at most the last 250 ms before a native crash, and replay
  judges native and low-memory deaths on Android 11+ only.

Full list: [docs/how-it-works.md#limitations](docs/how-it-works.md#limitations).

## Demo app

[`examples/demo`](examples/demo) is a Views + Compose shop with three planted
"can't reproduce" bugs: bad server data, a network race and a stale cache
after an upgrade. Build it with your SDK token:

```bash
BUGCAPSULE_SDK_TOKEN=bcsdk.v1.… ./gradlew :demo:assembleDebug
```

Walkthrough and fix toggles: [docs/how-it-works.md](docs/how-it-works.md#demo-app).

## More documentation

| Topic | Where |
|---|---|
| Android SDK guide | [docs.bugcapsule.com/android](https://docs.bugcapsule.com/android.html) |
| Quickstart (30 minutes, capture to verified fix) | [docs.bugcapsule.com/quickstart](https://docs.bugcapsule.com/quickstart.html) |
| CLI reference | [docs.bugcapsule.com/cli](https://docs.bugcapsule.com/cli.html) |
| How capture, monitoring, uploads and replay work | [docs/how-it-works.md](docs/how-it-works.md) |
| Release symbols | [docs.bugcapsule.com/symbols](https://docs.bugcapsule.com/symbols.html) |
| Troubleshooting | [docs.bugcapsule.com/troubleshooting](https://docs.bugcapsule.com/troubleshooting.html) |
| Working on this repository | [docs/development.md](docs/development.md) |

## Support and license

Questions or access requests: [contact@bugcapsule.com](mailto:contact@bugcapsule.com).

This SDK is proprietary software of Bug Capsule. Use, copying, modification
and distribution require prior written permission. See [LICENSE](LICENSE).

### Keyboard state during pointer replay

Version 0.4.1-beta.6 records soft-keyboard visibility with pointer input and restores it before each gesture. Existing capsules without this field keep their original replay behavior; recapture with this SDK to include keyboard state. The runner does not bypass Android input permissions or ignore failed input.

## Optional capsule screenshots (unreleased)

Opt-in PNG frames are stored inside capsules and displayed against event time.
See [capture, privacy, limits and platform examples](docs/screenshots-v1.md).
