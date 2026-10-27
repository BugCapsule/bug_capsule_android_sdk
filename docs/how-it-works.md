# How the Android SDK works

Reference details behind the [README](../README.md): what is captured and
when, the full monitoring API, how replay runs, how data is stored and sent,
and the full list of limitations. The capsule format is documented at
[docs.bugcapsule.com/capsule-format](https://docs.bugcapsule.com/capsule-format.html).

## Contents

- [Capture](#capture)
- [Failure-capture boundaries](#failure-capture-boundaries)
- [Monitoring](#monitoring)
- [Replay](#replay)
- [Crash capsules](#crash-capsules)
- [Interaction dependencies](#interaction-dependencies)
- [`bugcapsule.json`](#bugcapsulejson)
- [Storage and uploads](#storage-and-uploads)
- [Demo app](#demo-app)
- [Limitations](#limitations)

## Capture

Supported frameworks: Android Views (Activities, Fragments, AppCompat),
Jetpack Compose (helpers), Jetpack Navigation (Fragment and Compose),
OkHttp 4 (and Retrofit on top), SharedPreferences, Preferences DataStore,
AndroidX ViewModel. Kotlin coroutines need nothing special (uncaught
coroutine exceptions reach the default handler).

- **Lifecycle**, process level through `ActivityLifecycleCallbacks`: first
  activity resumed → `resumed`, last paused → `inactive`, last stopped →
  `paused` (configuration changes ignored).
- **Pointers** by wrapping each Activity's `Window.Callback`
  (`dispatchTouchEvent`), as screen coordinates divided by density (dp). No
  accessibility service, no screen recording, no reflection.
- **Back key** (`key` event, Android extension): the system Back key the
  window receives (an IME consuming Back is not recorded).
- **Actions / text** only through the explicit helpers.
- **Navigation** destinations (`route`, `source: "navigation"`).
- **Network**: OkHttp exchanges; the response body source is tapped while the
  app reads it (same bytes and timing), keeping a bounded prefix (256 KiB text,
  64 KiB binary); one-shot/duplex request bodies are never read.
- **WebSockets** (`BugCapsuleWebSockets`, `bugcapsule-okhttp`) and **native
  SDK adapters** (`NativeAdapter`): see
  [Interaction dependencies](#interaction-dependencies).
- **Storage**: allowlisted SharedPreferences files/keys and DataStore stores
  in every checkpoint (launch, then every 30 s).
- **Failures**: `check`/`fail` assertions, `reportError`, and uncaught JVM
  exceptions on any thread.

The session anchor (event `t = 0`) is the first drawn frame of the first
activity; `t` uses `SystemClock.elapsedRealtime()`.

More helpers: `BugCapsule.log("legacy cache migrated", level = "warn")`,
`BugCapsule.reportState("cart")` (a diagnostic `state` event),
`BugCapsule.flushUploads()`, `BugCapsule.refreshRemoteConfig()`.

## Failure-capture boundaries

- **Captured automatically**: uncaught JVM exceptions on any thread, through a
  chained default `UncaughtExceptionHandler`. The previous handler is
  **always** called afterwards (the process still dies as before). The crash
  capsule is encoded and written **synchronously** to app-private storage
  (`noBackupFilesDir/bugcapsule`) and queued; it is uploaded on the **next
  launch**, never during process death. Such capsules carry `crash.persisted`
  (partial). `ActivityThread`'s `RuntimeException("Unable to start activity …")`
  wrapper is unwrapped to the app's exception.
- **No capsule for**: native (NDK) crashes, ANRs, `System.exit`/`killProcess`,
  OOM kills, exceptions the app catches without calling `reportError`, errors
  inside other processes. Assertions you do not write cannot fire. Native
  crashes and ANRs are still **monitoring events**, and OOM/force-quit kills
  in the foreground end the session as `abnormal`.
- At most 3 automatic capsules per session, one per failure signature;
  capsules are written 300 ms after the failure (crashes immediately).

## Monitoring

Error events, release-health sessions, transactions and feedback are sent as
gzip envelopes with the same credential. Monitoring runs in every capture
session, **whether or not the session records a capsule** (production
sampling only applies to capsules), never during replay. `enabled(false)` or
`monitoring(false)` turn it off.

### Options

```kotlin
BugCapsuleConfig.builder()
    .token(BuildConfig.BUGCAPSULE_TOKEN)
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

### API

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

### What is sent

- **Errors** (`event`): uncaught JVM exceptions (`fatal`, mechanism
  `uncaught`, written synchronously and sent next launch), `reportError` /
  `check` / `fail` failures, `captureException` / `captureMessage`, **ANRs**
  (watchdog: main thread blocked 5 s in the foreground, mechanism `anr`, main
  thread stack in `threads`) and **native crashes** with `bugcapsule-ndk`
  (mechanism `signal`, frames with `instructionAddr`, `elf` debug images with
  GNU build ids). Exception chains are innermost cause first, frames oldest
  first; `inApp` from the app id or `inAppIncludes`/`inAppExcludes`;
  `debugImages` carries the `proguard` UUID from
  `assets/bugcapsule-debug-meta.properties`. Contexts: `device`, `os`, `app`,
  `culture`, `screen`, `trace`.
- **Capsule link**: when a failure also writes a capsule, the event carries
  `capsuleId` and the capsule `manifest.monitoring = {eventId, traceId,
  release, environment}`; a manual `capture()` links the last failure's event.
- **Breadcrumbs** come from the flight recorder (route, action, text field
  id, Back key, http, lifecycle, log, state, failure), so nothing is
  instrumented twice; sessions that do not record a capsule still record
  these light events (OkHttp then records only method, URL, status, duration).
- **Sessions** (`session`): one per foreground run, ended 30 s after going to
  the background (`exited`), `crashed` on an uncaught or native crash,
  `abnormal` when the process died in the foreground (detected on the next
  launch). `did` is `user.id` or a random install id. `startSession()` /
  `endSession()` for apps that manage it.
- **Tracing** (`transaction`): activities (`ui.load`, with
  `time_to_initial_display` / `time_to_full_display`), cold/warm app start,
  fragments (child `ui.load` spans), Navigation destinations (`navigation`,
  named by route pattern), Compose screens, frames from JankStats
  (`frames_total|slow|frozen`, `frames_delay`), OkHttp `http.client` spans,
  SQLite/Room `db.sql.query` spans, `ui.action.click` for semantic actions
  (`enableUserInteractionTracing`). Screen transactions finish 3 s after their
  last span (at most 30 s) or when the activity pauses. Sampling is decided
  once at the root and propagated.
- **Trace propagation**: OkHttp requests to `tracePropagationTargets` get
  `traceparent` (unless the app set one) and `baggage` (app entries kept).
  Never in replay; recorded request headers and request keys are the app's
  own, so replay keys are unchanged.
- **Feedback** (`feedback`): `captureFeedback`, the Views dialog, the Compose
  sheet, shake to report; in QA mode (`captureCapsuleWithFeedback`) a capsule
  is written too and linked by `capsuleId`.

Delivery: a separate persisted outbox
(`noBackupFilesDir/bugcapsule/monitoring/envelopes`, 30 envelopes / 5 MiB /
7 days), sent 2 s after a write, on foreground and background, and by
`flush()`. The capsule outbox rules apply (only after a config fetch, backoff
30 s → 1 h, `Retry-After`, refused items dropped). Items over 1 MiB lose breadcrumbs,
then spans and frames, or are dropped. **Every item is redacted with the
capsule rules before it is written.**

Remote settings: `monitoringEnabled: false` stops envelopes and deletes queued
ones (capsules unaffected); `errorSampleRate`, `tracesSampleRate` (overrides
the app's rate unless it set a `tracesSampler`), `sessionTracking`,
`maxBreadcrumbs`, `tracePropagationTargets` (replaces the app's list).

### Native crashes (`bugcapsule-ndk`)

Add the artifact and `BugCapsule.init` installs it
(`enableNativeCrashHandling`, default on). A `sigaction` handler (SIGSEGV,
SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP; not SIGQUIT, which ART uses for ANR
dumps) on an alternate stack writes a text record with `write(2)` (signal,
code, fault address, thread, up to 64 frames) and chains to the previous
handler, so tombstones and other handlers still run. Limits: `dladdr` and the
unwinder are not formally async-signal-safe (best effort); stack overflows on
threads without an alternate signal stack cannot be recorded; symbols are the
nearest exported ones until unstripped `.so` files are uploaded (`kind=elf`).

The record also carries the scope: monitoring hands the handler a redacted JSON
copy of the global scope (breadcrumbs, user, tags, extras, contexts, `app`
included, at most 64 KiB, oldest breadcrumbs dropped first) at most every
100 ms, double-buffered so the handler always reads a complete copy. The event
sent on the next launch uses that scope and the crashed process's release.

### How the previous process ended (`ApplicationExitInfo`, Android 11+)

On launch the SDK reads the app's recent exit records
(`enableExitInfoTracking`, default on) and reports the ones not reported yet
(the newest handled timestamp is kept in `monitoring/exit_info.json`; the first
launch only remembers, it reports nothing older):

| Exit | Event |
|---|---|
| `REASON_ANR` | `fatal` `ApplicationNotResponding`, mechanism `anr` (`data.source: exit_info`, `foreground`), the main thread as the exception, every thread from the ANR dump |
| `REASON_CRASH_NATIVE` | With an NDK record of that pid: the record's event plus the tombstone's other threads and images (Android 12+). Without one: signal, crashing thread, every thread and `elf` images from the tombstone (mechanism `signal`, `data.source: tombstone`); before Android 12 only the system's description |
| `REASON_LOW_MEMORY` in the foreground | `fatal` `OutOfMemory`, mechanism `oom` |

JVM crashes (`REASON_CRASH`) are not repeated: the uncaught handler reported
them. These processes could not write anything when they died, so monitoring
also keeps the scope in `monitoring/scope.json` (at most every second, with
the process id); an exit record of that pid gets that scope, release and
environment, any other gets none. Only the app's main process reads exit
records and keeps that file. An ANR the watchdog already reported for a
process is not reported again from its exit record. An exit is marked handled
only once its event is queued, so a launch that dies first reports it again
next time; on a fresh install the first ANR or crash counts too. Thread lists
from tombstones and ANR dumps keep the frames nearest the top of each stack
(250 for the crashed thread, 40 for the others, 1,500 in all) and drop the last
threads until they fit 448 KiB, below the server's 512 KiB limit. A native crash exit makes the recovered
session `crashed`; ANR and low-memory kills leave it `abnormal`.

JVM crashes and watchdog ANRs list every thread (`threads`): the failing one
first, then the main thread, then the others by id, within the same limits.

### Embedding (`CrashBridge`)

`dev.bugcapsule.android.embedding.CrashBridge` runs the NDK handler, the ANR
watchdog and the exit records without monitoring's sessions or outbox, for an
SDK that sends events itself (the Flutter SDK's plugin): `install(context,
release, environment, options)`, `updateScope(json)` (the embedding SDK's
redacted scope in event form), `takePendingEvents()` (monitoring-v1 event
JSON, kept in `noBackupFilesDir/bugcapsule-crash-bridge/events` until taken),
`lastRunCrashed`, `setListener` (ANRs while running). It refuses to install
when `BugCapsule.init` already runs monitoring in the process.

### Gradle plugin details

For every variant, `uploadBugCapsuleNativeSymbols<Variant>` uploads the
output of `merge<Variant>NativeLibs` (unstripped, before the strip task): each
`.so` with a GNU build id and a `.symtab` or `.debug_info` section, once per
build id, as `kind=elf` with `arch` and `name`. For non-debuggable variants it
runs after `assemble` / `bundle` when a URL and key are set
(`uploadNativeSymbols(false)` turns it off); debuggable ones upload only when
the task is run by hand.

For every variant with `isMinifyEnabled = true`, the
`io.github.bugcapsule.gradle` plugin writes
`assets/bugcapsule-debug-meta.properties` (`proguardUuid=<uuid>`, derived from
`mapping.txt`: the same mapping always gets the same id) and uploads the
gzipped `mapping.txt` after `assemble<Variant>` / `bundle<Variant>`. The URL
comes from `url`, `-PbugcapsuleUrl` or `BUGCAPSULE_URL`; the key from
`-PbugcapsuleSecretKey` or `BUGCAPSULE_SECRET_KEY`. `failOnUploadError(false)`
only warns. Re-uploading the same id replaces the file.

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

Per attempt the CLI clears the app (`pm clear`), forwards its loopback server
(`adb reverse tcp:<port>`), and runs
`am instrument -r -w -e bugcapsuleReplayUrl <url> -e class
dev.bugcapsule.android.replay.BugCapsuleReplayTest <testAppId>/<runner>`.

`BugCapsuleReplayRunner.onCreate` (before `Application.onCreate`) fetches the
capsule, **refuses unless the app is debuggable**, and restores
SharedPreferences/DataStore. `BugCapsule.init` then starts in replay mode. The
test launches the launcher activity with `ActivityScenario` and runs the
in-app engine: pointers via `Instrumentation.sendPointerSync` (dp → px, scaled
if the screen differs), Back via `sendKeySync`, actions and text on the main
thread, lifecycle by moving the scenario (`resumed`→RESUMED,
`inactive`→STARTED, `paused`→CREATED).

OkHttp requests are answered from the capsule by key + occurrence after the
recorded latency. Replay is strict: an unrecorded request fails with a
`ConnectException` and `network.unrecorded`. The result goes back to the CLI
over HTTP and as an instrumentation status line; a replayed crash reports
synchronously from the crash handler before the process dies. Long idle
stretches are compressed; recorded latency is kept.

The CLI builds `:<module>:assemble<Variant>` and
`assemble<Variant>AndroidTest` with `-PbugcapsuleGitSha=<sha>
-PbugcapsuleGitDirty=<bool>`, or uses `--apk` / `--test-apk`.
`BUGCAPSULE_ANDROID_GRADLE_ARGS` appends Gradle arguments for a single run.
`--device` picks a running device (serial, AVD name or model) or an AVD to
boot; an emulator the CLI booted is shut down afterwards. The device must be
unlocked once after boot (AVDs with a screen-lock PIN cannot be unlocked by
the CLI).

## Crash capsules

A native crash, an ANR the system killed the app for and a foreground
low-memory kill leave no time to write a capsule. A recording session
therefore keeps a **crash journal** on disk, and the next launch builds a
capsule from it and links it to the crash's fatal event (contract:
`docs/crash-capsules.md` in the backend repository).
Nothing changes for the app: no API, no option.

### The journal

Only sessions that record (the capsule sampling decision) keep one, in
`noBackupFilesDir/bugcapsule/journal/<pid>/`:

| File | Content |
|---|---|
| `header.json` | The manifest fields known while recording (`mode`, `app`, `source`, `sdk`, `platform`, `session`, `integrations`, `labels`, `redaction`, `runtime`, `capabilities`, every registered interaction dependency) plus `pid`, `release`, `environment`, `writtenAt` and `anchorT` (recorder time of the first frame). Rewritten when it changes: first frame, each periodic checkpoint, new redaction rules |
| `checkpoints/<afterSeq>.json` | The checkpoint the recorder's window starts at (the launch checkpoint until the recorder evicts) and the newest periodic one |
| `events/<firstSeq>.jsonl` | One event per line, as in a capsule; a new file starts at each checkpoint |

Event and checkpoint times are the recorder's (ms since the SDK started); the
builder shifts them to the session anchor. Recording only enqueues: one
background thread (`BugCapsule-Journal`) serializes and appends, with one
`write` per flush, at least every 250 ms and at once on a checkpoint, a
recorded failure, an ANR seen by the watchdog and `paused`. A native crash
loses at most the last 250 ms. When the recorder evicts events or moves its
window to a later checkpoint, the journal deletes the files it no longer
needs. On disk it holds at most `2 × maxCapsuleBytes`: past that it keeps only
the newest checkpoint and its events, and if that alone is too big it keeps
nothing until the next checkpoint. When remote config re-redacts or drops
buffered events, the journal is rewritten from the recorder. It is deleted
when recording stops (kill switch, rejected key, lowered sample rate) and
after a JVM crash, whose capsule the uncaught handler writes synchronously.

### Next launch

For each fatal event about an earlier process (an NDK record, an exit record
of `REASON_CRASH_NATIVE`, `REASON_ANR` or a foreground `REASON_LOW_MEMORY`)
whose pid has a journal younger than 7 days, the SDK builds the capsule before
it queues the event:

- `checkpoint`: the first kept checkpoint whose following events are all in
  the journal, else the newest periodic one (`events.window` gap); the newest
  periodic one also when the first capsule is over `maxCapsuleBytes`; no
  capsule when that is still too big;
- `manifest.failure`: kind `crash`, `signature` `crash:<type>` (`SIGSEGV`,
  `ApplicationNotResponding`, `OutOfMemory`, `NativeCrash` when Android had
  no tombstone), `crash: {mechanism, signal?, inAppFrame?}`, the crashed
  frames as `stack`, `t` = crash time relative to the anchor;
- `manifest.monitoring` = `{eventId, release, environment}` of the event, and
  the event's `capsuleId` = the capsule;
- eligibility gap `crash.native` (a crash capsule is at best `partial`), plus
  the usual integration, dependency and checkpoint checks;
- `labels.source = crash_journal`, `labels["crash.mechanism"]` (`signal`,
  `anr`, `oom`); `stats.journalGapMs` = crash time − last journaled event;
- format v2 always (`dependencies` may be empty).

The capsule is saved, queued in the outbox and the journal deleted. Crash
capsules do not count against `maxCapsulesPerSession`. An ANR the watchdog
already reported while the app ran is not sent again; its capsule links to the
watchdog's event (`manifest.monitoring.eventId`). Journals that no fatal event
claims, and any older than 7 days, are deleted once the previous processes
have been reported. `CrashBridge` builds no capsules (the Flutter SDK journals
on the Dart side); its events of earlier processes carry
`contexts.process.pid` so the embedding SDK can find its own journal.

### Replaying a crash capsule

The engine replays the events as usual and judges the attempt by how the app
under test dies:

| What happens | Report |
|---|---|
| The process dies with the same `type` (same signal; ANR; low-memory kill) | `outcome: reproduced`; `reasonCode: failure.different_frame` when both sides know the in-app frame and it differs |
| The process dies with another type (another signal, an uncaught JVM exception, an ANR for a signal capsule) | `outcome: inconclusive`, `reasonCode: failure.different` |
| The window ends and the process is alive | `outcome: not_reproduced` |

An uncaught JVM exception reports synchronously from the crash handler. The
main thread blocked for 5 s is an ANR: a watchdog reports it at once, while
the process lives (`death.source: watchdog`). A native signal or a
low-memory kill ends the process before it can report: the engine wrote
`noBackupFilesDir/bugcapsule-replay/crash-attempt.json` (attempt, pid,
expected crash) when the run started, and deletes it whenever it reports.

**What the CLI must do.** When `am instrument` ends without a
`bugcapsule_result` (the instrumentation reports `Process crashed`), run the
post-mortem with the same replay URL, before clearing the app:

```
am instrument -r -w -e bugcapsuleReplayUrl <url> -e bugcapsuleReplayPostMortem true \
  -e class dev.bugcapsule.android.replay.BugCapsuleReplayTest#judgeProcessDeath <testAppId>/<runner>
```

It fetches `/settings` (for the `attemptId`), reads the marker, waits up to
10 s for the system's exit record of the marked pid (Android 11+), judges it
and posts the report to `/result` and as the status line, like a replay.
`BugCapsule.init` stays off in that process, and the exit is marked handled so
the app's next capture launch does not report a replay crash. Android 10 and
older have no exit records: the post-mortem reports `error`, and the CLI can
judge from `adb logcat -b crash` (`Fatal signal <n> (<SIG…>)` of the pid).

**Result fields** (in addition to the usual ones):

| Field | Meaning |
|---|---|
| `outcome` | `reproduced`, `not_reproduced`, or **`inconclusive`** (new; older readers parse it as `error`, also an invalid attempt) |
| `reasonCode` | `failure.different` (with `inconclusive`), `failure.different_frame` (with `reproduced`), else absent |
| `death` | How the app died: `{mechanism: signal\|anr\|oom\|uncaught, type, signal?, pid?, source: uncaught\|watchdog\|exit_info\|tombstone, inAppFrame?}`; absent while it lives |
| `observed` | The death as a failure (kind `crash`, `signature` `crash:<type>`) |
| `expectedSignature` | `crash:<type>` of the capsule |

`inconclusive` attempts are invalid: `verify` keeps its rules (a batch with an
invalid attempt is `inconclusive`; `not_reproduced` only when every attempt
is). `Verdicts` adds the attempt's `reasonCode` to the batch's reason codes.

## Interaction dependencies

WebSockets and third-party SDKs are recorded as *interaction dependencies*
(capsule format v2, [docs.bugcapsule.com/capsule-format](https://docs.bugcapsule.com/capsule-format.html)).
A capsule is written as v2 only when it contains at least one; sessions
without them stay v1. The SDK reads v1 and v2 and rejects newer versions.

Each adapter adds one `manifest.dependencies` entry: adapter id and version,
payload schema, recorded operations, the library versions it was tested
against, gaps (why coverage is `partial`) and counters. A dependency with
gaps also gets a `dependency.<id>` eligibility entry.

**Bounds** (shared by every adapter of a session): 64 KiB per payload
(larger ones are recorded as `truncated`), 1 MiB of payloads in a rolling
5-minute window (later ones keep only their size), 32 connections and
subscriptions open at once (further ones run uncaptured with a
`connections.limit` gap). Payloads go through the session's redactor; a
redacted payload is not served in replay unless the adapter declares it
safe. Binary data is recorded as size only unless the adapter opts in. With
network body capture off, payloads are recorded as `omitted`. The project's
capture settings can turn off one adapter by id; its buffered events are
dropped and HTTP capture continues.

**OkHttp WebSockets** (`bugcapsule-okhttp.websocket`, schema 1, tested with
OkHttp 4.12.0). Only sockets created with `BugCapsuleWebSockets.factory(client)`
or `newWebSocket(client, request, listener)` are captured. Recorded:
`ws.connect` (connection id, redacted URL, match key = redacted URL without
ignored query parameters, occurrence, reconnect attempt = consecutive failed
connects for that key, requested protocols), `ws.open`, `ws.send` (with the
`Boolean` OkHttp returned), `ws.message`, `ws.error` (`connect` or `stream`
phase, HTTP status of a failed handshake), the app's `close`/`cancel`
(`ws.close`, `initiator: client`), and the listener's `onClosing`/`onClosed`
(`ws.close` with `callback: closing|closed`).

In replay the adapter never connects. A connect is matched by key and
occurrence (identical concurrent connections by invocation order); sends and
closes are compared in order and return the recorded result; recorded
callbacks follow the app's own operations at the recorded latency. Listener
callbacks run on one background thread, `BugCapsule-WebSocket`, one at a
time in recorded order across all sockets (never on the main thread).

**Native adapters** implement `NativeAdapter` and go through
`NativeInteractions.call` (`native.call` / `result` / `error`) and
`subscribe` (`native.subscribe` / `event` / `error` / `done` / `cancel`).
Operations match by scope (the operation name, or `<operation>@<instance>`
when the wrapper names an SDK instance) plus a hash of the redacted
arguments (fields the adapter marks nondeterministic are left out) and
invocation order. Replayed callbacks run on `NativeAdapter.callbackExecutor`,
by default one background thread per adapter, and receive decoded values
(`ByteArray`, `Long`, primitive arrays, maps), the same values capture
handed over. Operations not in `NativeAdapter.operations` run uncaptured
with an `operation.unsupported` gap.

Replay never reaches the real dependency. An operation with no recording
fails, also when the capsule has no recording for the adapter at all and
for diagnostic adapters (`required = false`, recorded for diagnosis only):
WebSocket connects through `onFailure`, native calls and subscriptions with
`NativeError.UNRECORDED`, each with a `ws.unrecorded` / `native.unrecorded`
divergence. `NativeAdapterConformance` (`bugcapsule-android-replay`) checks an
adapter end to end in a JVM unit test.

**Replay checks.** Before the app starts, every required dependency needs
an installed adapter with the same id and a supported schema, and open
connections at a periodic checkpoint must be restorable; otherwise the
attempt ends `incompatible` with a message starting with
`dependency.unsupported`, `dependency.schema` or `dependency.unrestorable`.
During replay:

| Divergence | Meaning |
|---|---|
| `ws.unrecorded`, `native.unrecorded` | An operation with no recording; it fails (`onFailure` with `ConnectException`, `NativeError.UNRECORDED`) |
| `ws.mismatch`, `native.mismatch` | Matched, but the payload or arguments differ |
| `ws.noPayload`, `native.noPayload` | A recorded value replay cannot hand over (metadata only, truncated, redacted) |
| `ws.undelivered`, `native.undelivered` | A due event had no handler for 5 s |
| `ws.unused`, `native.unused` | Recorded at or before the failure and never matched or delivered |
| `ws.unusedAfterFailure`, `native.unusedAfterFailure` | The same after the failure (expected) |
| `dependency.live` | The SDK was reached during replay |

All but `*.unusedAfterFailure` keep `verify` from reporting a fix. A
delivery that is due keeps idle compression from skipping past it.

## `bugcapsule.json`

```jsonc
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

## Storage and uploads

The token (or explicit backend) gives the API address and credential. The SDK
fetches its configuration and uploads capsules and envelopes over
`HttpURLConnection` on its own background thread (the SDK's own traffic is
never captured).

- Uploads happen only after a successful config fetch, or with a cached one
  younger than 7 days.
- Outbox: 10 capsules / 10 MiB / 7 days, oldest evicted first.
- Retries: 30 s → 1 h with ±20 % jitter and `Retry-After` for network errors,
  timeouts and temporary server errors; a capsule BugCapsule refuses is
  dropped.
- Remote config is cached per address and credential, refreshed after its TTL
  (at least 30 s), with 30 s → 30 min backoff, and on foreground.
- A refused credential stops capture for the session (the queue is kept) and
  is logged once with the reason and advice. An upload refused for its
  credential refreshes the configuration first.
- Network failures, timeouts and server errors never reach the app: the status says
  `offline` and data stays queued.
- Production sessions record with probability
  `min(productionSampleRate (0.1), project sample rate)`.
- Storage never crashes the app: atomic writes, temp files cleaned at start,
  corrupt files dropped, a full disk drops the capsule.
- Crash journals (`journal/<pid>/`) are already-redacted capsule data with
  the same protection as saved capsules (`noBackupFilesDir`, never uploaded
  as they are); see [Crash capsules](#crash-capsules).
- The kill switch stops recording and deletes queued and saved capsules.
  Remote config can also disable body capture (also stripping buffered
  bodies) and add redaction keys (re-redacting the buffer).

## Demo app

`examples/demo` is a Views + Compose shop: Retrofit on OkHttp, an in-process
`FakeShopServer` interceptor below the SDK hook with random latency and data,
Jetpack Navigation, allowlisted SharedPreferences and DataStore, and
`CartViewModel` registered as state. Three planted bugs, all captured
automatically:

1. **Data-dependent**: 1 in 4 carts contain an item with `price: null`;
   Checkout throws `IllegalStateException("Item legacy-pin has no price")`,
   caught and sent with `BugCapsule.reportError`. Also, coupon `SAVE100` on a
   cart under 100 fires `BugCapsule.check("cart_total_non_negative")`.
2. **Timing-sensitive network**: "Refresh home" loads profile and
   recommendations in parallel; when recommendations arrive first (~1 in 4)
   the app crashes.
3. **State restoration**: "Simulate upgrade from v0.9" writes a legacy cart
   cache to `shop_prefs`; after a restart, opening the cart crashes with
   `JSONException`. Replay reproduces it only because the launch checkpoint
   restores `shop_prefs`.

Monitoring is on in the demo: every screen is traced, errors from the planted
bugs arrive as events linked to their capsules, and shaking the device opens
the feedback dialog.

Build with `BUGCAPSULE_SDK_TOKEN=bcsdk.v1.… ./gradlew :demo:assembleDebug`.
With an explicit address instead, set `BUGCAPSULE_INGEST_KEY=bct_…` and pass
`-PbugcapsuleBackendUrl=https://api.bugcapsule.com`. The demo's
`bugcapsule.json` points the CLI at this Gradle build (`gradleDir: "../.."`,
module `demo`).

**Fix toggle.** `-PbugcapsuleDemoFix=race` (or `BUGCAPSULE_DEMO_FIX=race`;
comma-separated `race,price,legacy`) becomes `BuildConfig.DEMO_FIX`, with no
tracked source changes:

```bash
BUGCAPSULE_ANDROID_GRADLE_ARGS=-PbugcapsuleDemoFix=race bugcapsule verify <id> -p examples/demo   # fix applied
bugcapsule verify <id> -p examples/demo                                                        # fix reverted
```

Only `race` keeps the recorded request sequence; `price` and `legacy` change
which requests the app makes, so verifying those capsules is (correctly)
`INCONCLUSIVE` with `network.unrecorded`.

## Limitations

- Replay runs only through the instrumentation runner on an emulator/device
  with a debuggable build; the replay screen should match the capture screen
  for pointer replay (otherwise coordinates are scaled, `screen.size`).
- Only the Back key is recorded among hardware keys; IME text arrives through
  tracked fields only. Multi-window and system dialogs are not driven.
- OkHttp replay answers in the interceptor: interceptors added after it,
  network interceptors, caching and cookies are not exercised in replay.
  Custom `RequestBody`s that are not one-shot must be re-writable.
- Clock/random determinism only through `BugCapsule.currentTimeMillis()` and
  `BugCapsule.random()`. WorkManager, alarms and Bluetooth are not captured.
  Other network stacks (HttpURLConnection, Ktor, Cronet, WebSockets not opened
  through `BugCapsuleWebSockets`) and SDKs such as location, maps or Firebase
  are captured only through an adapter (`NativeAdapter`).
- Interaction replay: connections and subscriptions open at a periodic
  checkpoint are not restored (the OkHttp WebSocket adapter has no snapshot;
  native adapters only with `NativeAdapter.restorable`). Ping/pong and frame
  details are not recorded. Event order across WebSockets follows the app's
  own operations in replay; only events due at the same time are ordered by
  their recorded sequence. A listener that blocks delays every replayed
  socket.
- Monitoring: coroutines do not carry the current span (`startSpan` is per
  thread; HTTP and database spans attach to the scope's transaction); the
  ANR watchdog reports hangs while the process is alive (ANRs that kill the
  app before 5 s are `abnormal` sessions); NDK frames are not symbolicated on
  device and the NDK handler is not yet verified on devices (host smoke test
  and builds only).
- Crash capsules: events of the last 250 ms before a native crash can be
  missing (`stats.journalGapMs`); HTTP requests in flight at the crash are not
  in the capsule (replay sees them as unrecorded); integration eligibility is
  checked by the next launch's integrations; replay judges native and
  low-memory deaths only on Android 11+ (exit records), and the in-app frame
  only from Android 12+ tombstones and ANRs.
- Navigation arguments that are Parcelables are not restored; state needs
  registered serializers. Periodic checkpoints are used only when the session
  outgrows the 5-minute / 20,000-event window.
