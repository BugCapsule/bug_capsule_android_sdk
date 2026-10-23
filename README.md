# BugCapsule for Android

BugCapsule turns a failure seen in QA into a portable `.bugcapsule` file that a
developer can **replay against the real app code** on an emulator, **verify**
after a fix, and keep as a **regression test**. It also does production
**monitoring** (error events with ANRs and native crashes, release-health
sessions, performance tracing, user feedback; see [Monitoring](#monitoring)),
linking each error to the capsule that replays it. This repository is the native
Android SDK (Kotlin, Java-friendly API), its instrumentation replay runner, and
a demo shop app. The capsule format and protocols are defined in
`bug_capsule_flutter_sdk/spec/capsule-v1.md` and `spec/runtimes-v1.md`; the
`bugcapsule` CLI (Dart) drives replay through `AndroidLauncher`.

| Artifact (`io.github.bugcapsule:…:0.4.1-beta.1`) | What it is |
|---|---|
| `bugcapsule-android` | Core: capsule model/codec/validator, recorder, redaction, request keys, remote config, outbox, capture (lifecycle, pointers, Back key, failures, crashes), replay engine, Views helpers, SharedPreferences and Preferences DataStore checkpoints, state serializers/restorers (incl. ViewModels) |
| `bugcapsule-okhttp` | OkHttp capture and deterministic replay (Retrofit works through it); `http.client` spans and trace propagation |
| `bugcapsule-navigation` | Jetpack Navigation: route events, checkpoint, restore; `navigation` transactions |
| `bugcapsule-compose` | Compose helpers: semantic actions, tracked text, navigation effect, screen tracing, feedback sheet |
| `bugcapsule-ndk` | NDK crash handler (signal handler writing a crash record, sent on next launch) |
| `bugcapsule-sqlite` | SQLite / Room `db.sql.query` spans (`SupportSQLiteOpenHelper.Factory` wrapper) |
| Gradle plugin `io.github.bugcapsule.gradle` | R8/ProGuard mapping UUID in the APK + `mapping.txt` upload (`bugcapsule-gradle-plugin/`, an included build) |
| `bugcapsule-android-replay` | **androidTest only**: `BugCapsuleReplayRunner` (an `AndroidJUnitRunner`) and `BugCapsuleReplayTest` |

Requirements: minSdk 23, compileSdk 34+, Kotlin or Java 17 bytecode. Preferences
DataStore checkpoints need `androidx.datastore:datastore-preferences` 1.1+ in the app.

## Quickstart

```kotlin
// build.gradle.kts (app)
android {
    defaultConfig {
        testInstrumentationRunner = "dev.bugcapsule.android.replay.BugCapsuleReplayRunner"
        // The CLI passes -PbugcapsuleGitSha=<sha> so capsules and replays carry the build's commit.
        buildConfigField("String", "BUGCAPSULE_GIT_SHA", "\"${providers.gradleProperty("bugcapsuleGitSha").getOrElse("")}\"")
        buildConfigField("String", "BUGCAPSULE_INGEST_KEY", "\"${providers.environmentVariable("BUGCAPSULE_INGEST_KEY").getOrElse("")}\"")
    }
    buildFeatures.buildConfig = true
}
dependencies {
    implementation("io.github.bugcapsule:bugcapsule-android:0.4.1-beta.1")
    implementation("io.github.bugcapsule:bugcapsule-okhttp:0.4.1-beta.1")
    implementation("io.github.bugcapsule:bugcapsule-navigation:0.4.1-beta.1")
    implementation("io.github.bugcapsule:bugcapsule-compose:0.4.1-beta.1")       // if you use Compose
    implementation("io.github.bugcapsule:bugcapsule-ndk:0.4.1-beta.1")           // native crash events (optional)
    implementation("io.github.bugcapsule:bugcapsule-sqlite:0.4.1-beta.1")        // SQLite / Room spans (optional)
    androidTestImplementation("io.github.bugcapsule:bugcapsule-android-replay:0.4.1-beta.1")
}
```

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing in Application.onCreate (not a ContentProvider / App Startup
        // initializer): replay restores storage right before Application.onCreate.
        BugCapsule.init(
            this,
            BugCapsuleConfig.builder()
                .mode(CaptureMode.QA)                       // default: DEBUG if debuggable, else PRODUCTION
                .backend("https://bugcapsule.example.com", BuildConfig.BUGCAPSULE_INGEST_KEY) // bci_… only
                .gitSha(BuildConfig.BUGCAPSULE_GIT_SHA)
                .redactKeys("loyaltyId")
                .build(),
            listOf(okHttp, navigation,
                BugCapsuleSharedPreferences.builder().allowFile("shop_prefs").build(),
                BugCapsuleDataStore.builder().allow("settings").build()),
        )
    }
    companion object {
        val okHttp = BugCapsuleOkHttp(rules = listOf(
            EndpointRule("POST", "/api/checkout", ignoreBodyFields = setOf("timestamp", "nonce"))))
        val navigation = BugCapsuleNavigation()
    }
}

val client = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor())          // your interceptors first: they still run in replay
    .addInterceptor(ShopApp.okHttp.interceptor) // BugCapsule: the innermost application interceptor
    .build()
```

Java:

```java
BugCapsuleConfig config = BugCapsuleConfig.builder().mode(CaptureMode.QA).backend(url, key).build();
BugCapsule.init(app, config, Arrays.asList(okHttp, navigation));
BugCapsuleViews.onClick(button, "cart.checkout", v -> viewModel.checkout());
BugCapsule.check("cart_total_non_negative", total >= 0, "total " + total);
```

### Integration examples

```kotlin
// Views: semantic actions and tracked text (password input types are always sensitive).
BugCapsuleViews.onClick(checkoutButton, "cart.checkout") { viewModel.checkout() }
BugCapsuleViews.trackText(couponField, "cart.coupon")
BugCapsuleViews.trackText(passwordField, "login.password")

// Compose
val coupon = rememberTrackedText("cart.coupon")
TextField(value = coupon.value, onValueChange = coupon::onValueChange)
val password = rememberTrackedText("login.password", sensitive = true)
Button(onClick = rememberBugCapsuleAction("cart.checkout") { viewModel.checkout() }) { Text("Checkout") }
BugCapsuleNavigationEffect(navController, ShopApp.navigation)

// Jetpack Navigation (Fragments): after the graph is set
ShopApp.navigation.attach(navHostFragment.navController)

// Assertions, explicit failures, logs
BugCapsule.check("cart_total_non_negative", total >= 0, "total $total")
BugCapsule.fail("checkout_stuck", "spinner for 30 s")
try { … } catch (e: IllegalStateException) { BugCapsule.reportError(e) }
BugCapsule.log("legacy cache migrated", level = "warn")

// State in periodic checkpoints (restored in replay when registered)
BugCapsule.registerViewModel(this, "cart", { state.toJson() }) { value -> state = CartState.fromJson(value) }
BugCapsule.registerState("session", { sessionStore.snapshot() }) { sessionStore.restore(it) }
BugCapsule.reportState("cart")             // diagnostic `state` event

// Deterministic hooks: replay returns the recorded values
val nonce = BugCapsule.random().nextInt()
val now = BugCapsule.currentTimeMillis()

// Manual capture, uploads, remote config
BugCapsule.capture(note = "QA: total looks wrong")   // Future<CaptureResult?>
BugCapsule.flushUploads(); BugCapsule.refreshRemoteConfig()
```

## Supported frameworks

Android Views (Activities, Fragments, AppCompat), Jetpack Compose (helpers),
Jetpack Navigation (Fragment and Compose), OkHttp 4 (and Retrofit on top),
SharedPreferences, Preferences DataStore, AndroidX ViewModel. Kotlin
coroutines need nothing special (uncaught coroutine exceptions reach the
default handler).

## What is captured, and how

* **Lifecycle**, process level through `ActivityLifecycleCallbacks`: first
  activity resumed → `resumed`, last paused → `inactive`, last stopped →
  `paused` (configuration changes ignored).
* **Pointers** by wrapping each Activity's `Window.Callback`
  (`dispatchTouchEvent`), as screen coordinates divided by density (dp). No
  accessibility service, no screen recording, no reflection.
* **Back key** (`key` event, Android extension): the system Back key the
  window receives (an IME consuming Back is not recorded).
* **Actions / text** only through the explicit helpers above.
* **Navigation** destinations (`route`, `source: "navigation"`).
* **Network**: OkHttp exchanges; the response body source is tapped while the
  app reads it (same bytes and timing) keeping a bounded prefix (256 KiB text,
  64 KiB binary); one-shot/duplex request bodies are never read.
* **Storage**: allowlisted SharedPreferences files/keys and DataStore stores
  in every checkpoint (launch, then every 30 s).
* **Failures**: `check`/`fail` assertions, `reportError`, and uncaught JVM
  exceptions on any thread.

The session anchor (event `t = 0`) is the first drawn frame of the first
activity; `t` uses `SystemClock.elapsedRealtime()`.

### Failure-capture boundaries (precise)

* **Captured automatically**: uncaught JVM exceptions on any thread, through a
  chained default `UncaughtExceptionHandler`. The previous handler is **always**
  called afterwards (the process still dies as before). The crash capsule is
  encoded and written **synchronously** to app-private storage
  (`noBackupFilesDir/bugcapsule`) and queued; it is uploaded on the **next
  launch** — no upload is attempted during process death. Such capsules carry
  `crash.persisted` (partial). `ActivityThread`'s `RuntimeException("Unable to
  start activity …")` wrapper is unwrapped to the app's exception.
* **No capsule for**: native (NDK) crashes, ANRs, `System.exit`/`killProcess`,
  OOM kills, exceptions the app catches without calling `reportError`, errors
  inside other processes. Assertions you do not write cannot fire. Native
  crashes and ANRs are still **monitoring events**, and OOM/force-quit kills
  in the foreground end the session as `abnormal` (see [Monitoring](#monitoring)).
* At most 3 automatic capsules per session, one per failure signature; capsules
  are written 300 ms after the failure (crashes immediately).

## Monitoring

Implements [`monitoring-v1`](../bug_capsule_backend/docs/monitoring-v1.md):
error events, release-health sessions, transactions and feedback, sent as
gzip envelopes to `POST /v1/envelope` with the same ingest key. It runs in
every capture session, **whether or not the session records a capsule**
(production sampling only applies to capsules), never during replay, and
`enabled(false)` / `monitoring(false)` turn it off.

```kotlin
BugCapsuleConfig.builder()
    .backend(url, BuildConfig.BUGCAPSULE_INGEST_KEY)
    // release defaults to "<appId>@<versionName>+<versionCode>", environment to production/qa/development by mode
    .tracesSampleRate(0.2)                          // or .tracesSampler { ctx -> if (ctx.op == "app.start.cold") 1.0 else null }
    .tracePropagationTargets("api.shop.com", "/^https://.*\\.internal\\//")  // none by default
    .errorSampleRate(1.0)                           // fatal and capsule-linked events are always sent
    .inAppIncludes("com.shop").inAppExcludes("com.shop.vendor")   // default: the app id
    .beforeSend { event, hint -> if (hint.throwable is CancellationException) null else event }
    .beforeBreadcrumb { crumb -> crumb.takeUnless { it.category == "ui.input" } }
    .enableTimeToFullDisplayTracing(true)           // screens wait for BugCapsule.reportFullyDrawn()
    .enableShakeToReport(true)                      // shake opens the feedback dialog
    .build()
```

```kotlin
BugCapsule.captureException(e, CaptureOptions(tags = mapOf("flow" to "checkout"), fingerprint = listOf("cart-total")))
BugCapsule.captureMessage("legacy cart migrated", Level.WARNING)
BugCapsule.reportError(e)                         // failure: capsule + event, linked both ways
BugCapsule.setUser(User(id = "u_123", segment = "pro")); BugCapsule.setTag("plan", "pro")
BugCapsule.setContext("cart", mapOf("items" to 3)); BugCapsule.addBreadcrumb("coupon applied", "cart")
BugCapsule.withScope { scope -> scope.setTag("job", "sync"); BugCapsule.captureMessage("sync failed") }
BugCapsule.lastEventId(); BugCapsule.flush(5_000); BugCapsule.close()

val tx = BugCapsule.startTransaction("checkout", "task", bindToScope = true)
val span = tx.startChild("serialize", "cart"); span.setData("items", 3); span.finish(); tx.finish()
BugCapsule.startSpan("price cart", "task") { span -> repriceCart() }
val ctx = BugCapsule.continueTrace(incomingTraceparent, incomingBaggage)   // then startTransaction(..., context = ctx)
BugCapsule.getTraceparent(); BugCapsule.getBaggage()                     // for clients other than OkHttp
BugCapsule.reportFullyDrawn()                                            // TTFD of the current screen

BugCapsule.captureFeedback("Checkout button did nothing", name = "Ana", email = "ana@example.com", eventId = BugCapsule.lastEventId())
BugCapsuleFeedbackDialog.show(activity)                                   // Views form
if (show) BugCapsuleFeedbackSheet(onDismiss = { show = false })          // Compose (Material 3 sheet)
BugCapsuleScreen("Checkout"); BugCapsuleFullyDrawn(ready = state.loaded) // Compose screen transaction + TTFD
```

What is sent, and how:

* **Errors** (`event`): uncaught JVM exceptions (`fatal`, mechanism
  `uncaught`, written synchronously and sent next launch), `reportError` /
  `check` / `fail` failures, `captureException` / `captureMessage`, **ANRs**
  (watchdog: main thread blocked 5 s in the foreground, mechanism `anr`, main
  thread stack in `threads`) and **native crashes** with `bugcapsule-ndk`
  (mechanism `signal`, frames with `instructionAddr`: the exact PC for the
  crashing frame, return address − 1 for callers; `elf` debug images with
  GNU build ids, the crashed process's release). Exception chains are
  innermost cause first, frames oldest first; `inApp` from the app id or
  `inAppIncludes`/`inAppExcludes`; `debugImages` carries the `proguard` UUID
  from `assets/bugcapsule-debug-meta.properties`. Contexts: `device`, `os`,
  `app`, `culture`, `screen`, `trace`.
* **Capsule link**: when a failure also writes a capsule, the event carries
  `capsuleId` and the capsule `manifest.monitoring = {eventId, traceId,
  release, environment}`; a manual `capture()` links the last failure's event.
* **Breadcrumbs** come from the flight recorder (route, action, text field
  id, Back key, http, lifecycle, log, state, failure), so nothing is
  instrumented twice; sessions that do not record a capsule still record
  these light events (OkHttp then records only method, URL, status, duration).
* **Sessions** (`session`): one per foreground run, ended 30 s after going to
  the background (`exited`), `crashed` on an uncaught or native crash,
  `abnormal` when the process died in the foreground (detected on the next
  launch); the first error and the crash are sent with the event; `did` is
  `user.id` or a random install id. `startSession()` / `endSession()` for
  apps that manage it.
* **Tracing** (`transaction`): activities (`ui.load`, spans
  `ui.load.initial_display` at the first frame and `ui.load.full_display` at
  `reportFullyDrawn`, measurements `time_to_initial_display` /
  `time_to_full_display`), cold/warm app start (`app.start.cold|warm` span,
  `app_start_cold|warm`), fragments (child `ui.load` spans), Navigation
  destinations (`navigation`, named by route pattern), Compose screens,
  frames from JankStats (`frames_total|slow|frozen`, `frames_delay`), OkHttp
  `http.client` spans, SQLite/Room `db.sql.query` spans, `ui.action.click`
  for semantic actions (`enableUserInteractionTracing`). Screen transactions
  finish 3 s after their last span (at most 30 s) or when the activity
  pauses. Sampling is decided once at the root and propagated.
* **Trace propagation**: OkHttp requests to `tracePropagationTargets` get
  `traceparent` (unless the app set one) and `baggage` (app entries kept).
  Never in replay; the recorded request headers and request keys are the
  app's own, so replay keys are unchanged.
* **Feedback** (`feedback`): `captureFeedback`, the Views dialog, the Compose
  sheet, shake to report; in QA mode (`captureCapsuleWithFeedback`) a capsule
  is written too and linked by `capsuleId`.

Delivery: a separate persisted outbox (`noBackupFilesDir/bugcapsule/monitoring/envelopes`,
30 envelopes / 5 MiB / 7 days), sent 2 s after a write, on foreground and
background, and by `flush()`; the capsule outbox rules apply (only after a
config fetch, backoff 30 s → 1 h, `Retry-After`, 4xx dropped, items rejected
in a `200` never retried). Items over 1 MiB lose breadcrumbs, then spans and
frames, or are dropped. **Every item is redacted with the capsule rules
before it is written** (ids and timestamps are kept as they are).

Remote config (`/v1/sdk/config`): `monitoringEnabled: false` stops envelopes
and deletes queued ones (capsules unaffected; `enabled: false` stops both);
`errorSampleRate`, `tracesSampleRate` (overrides the app's rate unless it set
a `tracesSampler`), `sessionTracking`, `maxBreadcrumbs`,
`tracePropagationTargets` (replaces the app's list).

### Native crashes (`bugcapsule-ndk`)

Add the artifact: `BugCapsule.init` installs it by reflection
(`enableNativeCrashHandling`, default on). A `sigaction` handler (SIGSEGV,
SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP; not SIGQUIT, which ART uses for ANR
dumps) on an alternate stack writes a text record with `write(2)` (signal,
code, fault address, thread, up to 64 frames from `_Unwind_Backtrace` +
`dladdr`) and chains to the previous handler, so tombstones and other
handlers still run. Limits: `dladdr`/the unwinder are not formally
async-signal-safe (best effort); stack overflows on threads without an
alternate signal stack cannot be recorded; symbols are the nearest exported
ones until unstripped `.so` files are uploaded (`kind=elf`).

### Gradle plugin: ProGuard/R8 mapping upload

The `io.github.bugcapsule.gradle` plugin lets the backend deobfuscate stack traces
of minified builds. For every variant with `isMinifyEnabled = true` it writes
`assets/bugcapsule-debug-meta.properties` (`proguardUuid=<uuid>`, derived from
`mapping.txt`: the same mapping always gets the same id; the SDK sends it as a
`proguard` debug image) and uploads the gzipped `mapping.txt` to
`POST <url>/v1/debug-files?kind=proguard&debugId=<uuid>` after
`assemble<Variant>` / `bundle<Variant>`.

```kotlin
plugins { id("io.github.bugcapsule.gradle") version "0.4.1-beta.1" }
bugcapsule {
    url.set("https://bugcapsule.example.com")   // or -PbugcapsuleUrl / BUGCAPSULE_URL
    // secretKey: -PbugcapsuleSecretKey or BUGCAPSULE_SECRET_KEY (a bcs_… key: CI secrets only, never in the app)
    uploadProguardMapping.set(true)              // automatic upload when url and key are set
    failOnUploadError.set(true)                  // false: only warn
}
```

Without a url or key the id is still embedded and the upload is skipped; run
`./gradlew uploadBugCapsuleProguardMapping<Variant>` to upload by hand.
Re-uploading the same id replaces the file. Until the plugin is published,
use it as an included build (`pluginManagement { includeBuild("…/bugcapsule-gradle-plugin") }`).

### SQLite / Room (`bugcapsule-sqlite`)

```kotlin
Room.databaseBuilder(context, ShopDb::class.java, "shop.db")
    .openHelperFactory(BugCapsuleSQLiteOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))
    .build()
```

Queries, statements and insert/update/delete running while a span is active
become `db.sql.query` spans (description: the SQL; bound arguments are never
recorded).

## Replay

```
bugcapsule run <capsule> -n 3          # expects REPRODUCED
bugcapsule verify <capsule> -n 3       # after a fix: FIXED only if every attempt is valid and clean
bugcapsule regress add <capsule> && bugcapsule regress run
```

Per attempt the CLI clears the app (`pm clear`), forwards its loopback server
(`adb reverse tcp:<port>`), and runs
`am instrument -r -w -e bugcapsuleReplayUrl <url> -e class
dev.bugcapsule.android.replay.BugCapsuleReplayTest <testAppId>/<runner>`.
`BugCapsuleReplayRunner.onCreate` (before `Application.onCreate`) fetches the
capsule, **refuses unless the app is debuggable**, and restores
SharedPreferences/DataStore; `BugCapsule.init` then starts in replay mode; the
test launches the launcher activity with `ActivityScenario` and runs the
in-app engine: pointers via `Instrumentation.sendPointerSync` (dp → px, scaled
if the screen differs), Back via `sendKeySync`, actions/text on the main
thread, lifecycle by moving the scenario (`resumed`→RESUMED, `inactive`→STARTED,
`paused`→CREATED). OkHttp requests are answered from the capsule by key +
occurrence after the recorded latency (strict: unrecorded → `ConnectException`
+ `network.unrecorded`). The result goes to `POST <base>/result` and to the
instrumentation status `bugcapsule_result=BUGCAPSULE_RESULT::{json}`; a
replayed crash reports synchronously from the crash handler before the
process dies. Long idle stretches are compressed; recorded latency is kept.

**Production protection**: replay can only be armed from an `Instrumentation`
(the runner ships in the androidTest APK only) for a debuggable app. There is
no intent, deep link, URL, push or file path into replay.

## Privacy controls

Redaction happens on device before anything is written (capsule-v1
*Redaction*, conformance-tested against the Dart reference): sensitive keys
anywhere in bodies/storage/state/payloads, sensitive headers, URL user info and
sensitive query/fragment parameters, credentials in free text (bearer/basic,
JWTs, API key formats, `key=value` pairs, Luhn-valid card numbers). Add rules
with `redactKeys`, `redactHeaders`, `redactPattern(...)`; drop or rewrite with
`beforeRecord` / `beforeCheckpoint`; exclude endpoints with
`BugCapsuleOkHttp(exclude = …)`; allowlist storage (nothing is captured unless
allowlisted); mark text fields `sensitive` (password input types are forced).
Remote config can disable body capture (also strips buffered bodies), add
redact keys (re-redacts the buffer, drops buffered http events), lower
production sampling, or kill capture (stops recording, deletes queued and
saved capsules). Best effort: secrets in URL path segments, personal data that
is not a credential, and binary bodies are not detected automatically.

## Backend connectivity

`GET /v1/sdk/config` and `POST /v1/capsules` (gzip body,
`application/octet-stream`, `Authorization: Bearer bci_…`) over
`HttpURLConnection` (the SDK's own traffic is never captured). Uploads only
after a successful config fetch (or a cached one younger than 7 days); outbox
10 capsules / 10 MiB / 7 days, oldest evicted first; retry 30 s → 1 h with ±20 %
jitter and `Retry-After`; 401/408/429/5xx/network retry, other 4xx drop;
`capture_disabled` triggers a config refresh. Remote config is cached per
(backend URL, ingest key), refreshed after its TTL (min 30 s), with 30 s → 30
min backoff and on foreground. 401/403 stops capture for the session (queue
kept). Production sessions record with probability
`min(productionSampleRate (0.1), remote sampleRate)`. Storage never crashes the
app: atomic writes, temp files cleaned at start, corrupt files dropped,
disk-full drops the capsule. From an emulator the host is `10.0.2.2`; allow
cleartext only for local hosts (see the demo's `network_security_config.xml`).

## CLI configuration (`bugcapsule.json`)

```json
{
  "runtime": "android",
  "android": {
    "gradleDir": ".",               // Gradle root, relative to this file
    "module": "app",                // Gradle module path
    "moduleDir": "app",             // optional: module directory for APK lookup
    "variant": "debug",
    "applicationId": "com.example.shop",           // required
    "testApplicationId": "com.example.shop.test",  // default <applicationId>.test
    "runner": "dev.bugcapsule.android.replay.BugCapsuleReplayRunner",
    "testClass": "dev.bugcapsule.android.replay.BugCapsuleReplayTest",
    "avd": "Pixel_6_2",             // booted headless (-no-window -no-audio) when no device runs
    "gradleArgs": [],               // extra Gradle arguments
    "emulatorArgs": [],
    "sdkDir": "/path/to/android/sdk",  // default ANDROID_HOME, local.properties, ~/Library/Android/sdk
    "bootTimeoutSeconds": 300
  },
  "timeouts": {"buildSeconds": 1200, "attemptSeconds": 120}
}
```

The CLI builds `:<module>:assemble<Variant>` and `assemble<Variant>AndroidTest`
with `-PbugcapsuleGitSha=<sha> -PbugcapsuleGitDirty=<bool>` (from the git
checkout), or uses `--apk`/`--test-apk`. `BUGCAPSULE_ANDROID_GRADLE_ARGS`
appends Gradle arguments for a single run. `--device` picks a running device
(serial, AVD name or model) or an AVD to boot; an emulator the CLI booted is
shut down afterwards. The device must be unlocked once after boot (AVDs with a
screen-lock PIN cannot be unlocked by the CLI).

## Demo (`examples/demo`)

A Views + Compose shop (Retrofit on OkHttp, an in-process `FakeShopServer`
interceptor below the SDK hook with random latency/data, Jetpack Navigation,
allowlisted SharedPreferences and DataStore, `CartViewModel` registered as
state). Three planted bugs, all captured automatically:

1. **Data-dependent** — 1 in 4 carts contain an item with `price: null`;
   Checkout throws `IllegalStateException("Item legacy-pin has no price")`,
   caught and sent with `BugCapsule.reportError`. Also: coupon `SAVE100` on a
   cart under 100 fires `BugCapsule.check("cart_total_non_negative")`.
2. **Timing-sensitive network** — "Refresh home" loads profile and
   recommendations in parallel; when recommendations arrive first (~1 in 4) the
   app crashes (`IllegalStateException: Recommendations arrived before the profile`).
3. **State-restoration-dependent** — "Simulate upgrade from v0.9" writes a
   legacy cart cache (a JSON array) to `shop_prefs`; after a restart, opening
   the cart crashes with `JSONException`. Replay reproduces it only because the
   launch checkpoint restores `shop_prefs`.

Monitoring is on in the demo: every screen is traced, errors from the planted
bugs arrive as events linked to their capsules, and shaking the device opens
the feedback dialog (`bugcapsule-ndk` is included for native crash events).

Build: `./gradlew :demo:assembleDebug` (export `BUGCAPSULE_INGEST_KEY=bci_…`
to enable uploads; `-PbugcapsuleBackendUrl=` overrides `http://10.0.2.2:8787`).
The demo's `bugcapsule.json` points the CLI at this Gradle build
(`gradleDir: "../.."`, module `demo`).

### Fix toggle (isolated, build-time)

`-PbugcapsuleDemoFix=race` (or `BUGCAPSULE_DEMO_FIX=race`; comma-separated
`race,price,legacy`) becomes `BuildConfig.DEMO_FIX`. No tracked source changes:

```
BUGCAPSULE_ANDROID_GRADLE_ARGS=-PbugcapsuleDemoFix=race bugcapsule verify <id> -p examples/demo   # applied
bugcapsule verify <id> -p examples/demo                                                        # reverted
```

Only `race` keeps the recorded request sequence; `price`/`legacy` change which
requests the app makes, so verifying those capsules is (correctly)
inconclusive with `network.unrecorded`.

## Tests

```
./gradlew test                                   # every module, plus the Gradle plugin's tests
./gradlew :demo:connectedDebugAndroidTest        # emulator; unset BUGCAPSULE_INGEST_KEY first
bugcapsule-ndk/src/test/native/smoke.sh          # host smoke test of the signal handler (clang)
```

Monitoring is tested against a stubbed backend (MockWebServer speaking
monitoring-v1): envelopes, capsule links, sessions, sampling, remote config,
Retry-After, trace propagation, feedback.

The vendored `spec-fixtures/` must equal `../bug_capsule_flutter_sdk/spec/fixtures`
(`FixtureSyncTest`); re-sync with `bug_capsule_flutter_sdk/tool/sync_spec.sh`.

## Limitations

* Replay runs only through the instrumentation runner on an emulator/device
  with a debuggable build; the replay screen should match the capture screen
  for pointer replay (otherwise coordinates are scaled, `screen.size`).
* Only the Back key is recorded among hardware keys; IME text arrives through
  tracked fields only. Multi-window and system dialogs are not driven.
* OkHttp replay answers in the interceptor: interceptors added after it,
  network interceptors, caching and cookies are not exercised in replay.
  Custom `RequestBody`s that are not one-shot must be re-writable.
* Clock/random determinism only through `BugCapsule.currentTimeMillis()` and
  `BugCapsule.random()`. WorkManager, alarms, Bluetooth, location, other
  network stacks (HttpURLConnection, Ktor, Cronet) are not captured.
* Monitoring: coroutines do not carry the current span (`startSpan` is per
  thread; HTTP and database spans attach to the scope's transaction); the
  ANR watchdog reports hangs while the process is alive (ANRs that kill the
  app before 5 s are `abnormal` sessions); NDK frames are not symbolicated on
  device and the NDK handler is not yet verified on devices (host smoke test
  and builds only).
* Navigation arguments that are Parcelables are not restored; state needs
  registered serializers. Periodic checkpoints are used only when the session
  outgrows the 5-minute / 20,000-event window.

## Release procedure

1. Bump `VERSION_NAME` in `gradle.properties` (`SDK_VERSION` in BuildConfig follows).
2. `./gradlew clean publishAll` writes AARs, sources jars and POMs to
   `build/repo/dev/bugcapsule/<artifact>/<version>/` (a local Maven repository
   for review). Point a consuming project at it with
   `maven { url = uri("<path>/build/repo") }`.
3. Run the unit tests, `:demo:connectedDebugAndroidTest`, and the CLI E2E
   (`bugcapsule run/verify/regress`) on an emulator.
4. Publishing to Maven Central (signing, staging) is intentionally not wired up.

## Release validation (2026-10-23)

This repository is proprietary to Bug Capsule. Use, copying, modification, and distribution require prior written permission (see LICENSE). Local candidates are
prepared without publishing. See `docs/release-readiness.md` for the current
validation evidence and remaining release gates.
