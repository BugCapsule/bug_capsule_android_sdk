# BugCapsule for Android

BugCapsule turns a failure seen in QA into a portable `.bugcapsule` file that a
developer can **replay against the real app code** on an emulator, **verify**
after a fix, and keep as a **regression test**. This repository is the native
Android SDK (Kotlin, Java-friendly API), its instrumentation replay runner, and
a demo shop app. The capsule format and protocols are defined in
`bug_capsule_flutter_sdk/spec/capsule-v1.md` and `spec/runtimes-v1.md`; the
`bugcapsule` CLI (Dart) drives replay through `AndroidLauncher`.

| Artifact (`dev.bugcapsule:…:0.3.0`) | What it is |
|---|---|
| `bugcapsule-android` | Core: capsule model/codec/validator, recorder, redaction, request keys, remote config, outbox, capture (lifecycle, pointers, Back key, failures, crashes), replay engine, Views helpers, SharedPreferences and Preferences DataStore checkpoints, state serializers/restorers (incl. ViewModels) |
| `bugcapsule-okhttp` | OkHttp capture and deterministic replay (Retrofit works through it) |
| `bugcapsule-navigation` | Jetpack Navigation: route events, checkpoint, restore |
| `bugcapsule-compose` | Compose helpers: semantic actions, tracked text, navigation effect |
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
    implementation("dev.bugcapsule:bugcapsule-android:0.3.0")
    implementation("dev.bugcapsule:bugcapsule-okhttp:0.3.0")
    implementation("dev.bugcapsule:bugcapsule-navigation:0.3.0")
    implementation("dev.bugcapsule:bugcapsule-compose:0.3.0")       // if you use Compose
    androidTestImplementation("dev.bugcapsule:bugcapsule-android-replay:0.3.0")
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
* **Not captured**: native (NDK) crashes, ANRs, `System.exit`/`killProcess`,
  OOM kills, exceptions the app catches without calling `reportError`, errors
  inside other processes. Assertions you do not write cannot fire.
* At most 3 automatic capsules per session, one per failure signature; capsules
  are written 300 ms after the failure (crashes immediately).

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
./gradlew :bugcapsule-android:testDebugUnitTest :bugcapsule-okhttp:testDebugUnitTest :bugcapsule-navigation:testDebugUnitTest
./gradlew :demo:connectedDebugAndroidTest        # emulator; unset BUGCAPSULE_INGEST_KEY first
```

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
