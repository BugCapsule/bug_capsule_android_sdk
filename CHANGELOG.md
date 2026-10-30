# Changelog

## Unreleased

- Capsules with screenshots are no longer rejected: `capturedAtT` now moves with the event time when a capsule is rebased to its anchor. In release builds with a first frame over 1 s the backend answered `invalid_capsule` and dropped the whole capsule.
- Release-health sessions report `patchBaseBuildId` and `patchSetDigest`, so patch rollouts can be judged from real sessions. A session recovered on the next launch reports the patch it ran, not the current one.
- Emergency recovery (feature guards): `BugCapsule.emergency` fetches the project's signed policy document (ECDSA P-256, verified against the `bcek.v1.…` trust anchor in `EmergencyConfig`), caches it under the SDK's storage and evaluates `evaluate("checkout.confirm_order")` synchronously on the device: `ALLOW`, `BLOCK`, `READ_ONLY`, `REQUIRE_UPDATE`, `SHOW_MAINTENANCE`, `DISABLE_FEATURE` or `USE_SAFE_FALLBACK`, with the decision's `source`. Per-key failure modes (`FAIL_OPEN` by default, `FAIL_CLOSED`, `USE_LAST_VALID_POLICY`), anti-replay of older revisions, refresh every 60 s in the foreground with backoff and `Retry-After`, `If-None-Match`, receipts, listeners on the main thread and `status()`. It works without capture, and nothing is fetched while a capsule is replayed. A client guard is UX only: the server must enforce the same restriction. Implements emergency-client-v1 (`docs/emergency-client-v1.md`, vectors in `spec-fixtures/emergency-v1`).
- Guard decisions in capsules (emergency-client-v1 §7.8): decisions are recorded as `emergency.decision` events when they change, at most 256 feature key and subject pairs. Subjects are written only as a tag (first 16 hex characters of a SHA-256). Events go through redaction, `beforeRecord`, sampling and the kill switch. Decisions made before recording starts are written when it starts, and every tracked decision is written again after each periodic checkpoint. Idle compression ignores these events. `manifest.capabilities["emergency.decisions"]` is `supported`, or `partial` once the capture rules dropped a decision or changed its `key` or `action`, or the limit was reached; the crash journal header is refreshed when it turns partial. During replay, including crash capsules, `evaluate` returns the recorded decision with source `REPLAY`, or ALLOW when the capsule has none for the key; update providers are not called. Vectors in `spec-fixtures/emergency-v1/replay-decisions.json`.
- Emergency store updates: `BugCapsule.emergency.updates` (`checkForUpdate`, `startUpdate`, `resumeUpdate`, `completeUpdate`, `status`, listeners on the main thread) drives an `EmergencyUpdateProvider` set with `EmergencyConfig.updateProvider(…)`, for `REQUIRE_UPDATE` decisions (`EmergencyUpdateRequest.from(decision)`; decisions now carry the deciding policy's `severity`). New value types `EmergencyUpdateAvailability`, `EmergencyUpdateStatus`, `EmergencyUpdateFlow` and `EmergencyUpdateState`. Apps distributed outside Google Play implement the provider themselves; the SDK never installs a package.
- Runtime reports (emergency-client-v1 §7.6): each update status change is sent as a `RUNTIME` receipt with the highest accepted revision and a validated `runtime.update` object, never the same object twice in a row, at most one every 60 s (coalesced to the latest), fire-and-forget, nothing in replay mode. `APPLIED` and `FAILED` receipts carry the latest runtime.
- New artifact `bugcapsule-android-play`: `PlayEmergencyUpdateProvider` on Google Play In-App Updates (`com.google.android.play:app-update` 2.1.0). Flexible flow by default; immediate only when the app opts in (`allowImmediateUpdates(true)`), the decision is `REQUIRE_UPDATE` with severity `P0`/`P1` and Play allows it. `required_version` becomes a version code through `versionCode(name, code)` or its build number (`2.4.2+57` → 57); without either any newer Play update counts. Activity results and install states map to `in_progress`, `downloaded`, `declined` and `failed`; a downloaded update is installed only on `completeUpdate()`; `resumeUpdate` restarts an interrupted immediate update.

## 0.4.1-beta.6 — 2026-10-28

- Apply the project's `maxInteractionPayloadBytes` capture setting to every interaction adapter (`RemoteCaptureConfig.maxInteractionPayloadBytes`).
- Crash capsules keep at most 98 app labels, so the two crash labels fit and an app with 99 or more labels no longer loses its crash capsule to validation.

## 0.4.1-beta.5 — 2026-10-27

- Crash capsules: a recording session keeps a crash journal (`journal/<pid>/` in the SDK's storage: header, checkpoints, events; one background writer, flushed every 250 ms, at most `2 × maxCapsuleBytes`). The next launch builds a capsule (format v2, failure kind `crash`, gap `crash.native`, `labels.source = crash_journal`, `stats.journalGapMs`) for a native crash, an ANR kill or a foreground low-memory kill of that process, queues it and links it to the crash's event (`capsuleId`, `manifest.monitoring`). Crash capsules do not count against `maxCapsulesPerSession`. An ANR the watchdog already reported gets a capsule linked to that event.
- Capsule reader and validator accept failure kind `crash` (v2) with `failure.crash` and `stats.journalGapMs`.
- Replay of crash capsules: the attempt is `reproduced` when the app dies the same way (same signal, ANR, low-memory kill), `inconclusive` with `reasonCode: failure.different` when it dies another way, `not_reproduced` when it lives to the end of the window. Reports gain the outcome `inconclusive`, `reasonCode` and `death`. A post-mortem step (`-e bugcapsuleReplayPostMortem true`, `BugCapsuleReplayTest#judgeProcessDeath`) reports an attempt whose process died before it could, from the system's exit record.
- `CrashBridge` events of earlier processes carry `contexts.process.pid`.

- Crash reporting: recovered `crashed` / `abnormal` sessions keep the release and environment they ran with, so crash-free rates no longer blame the next release after an update.
- Native crashes reported on the next launch carry the crashed process's breadcrumbs, user, tags and contexts (`bugcapsule-ndk` writes a mirrored copy of the scope into its crash record).
- Android 11+: report how the previous process ended from `ApplicationExitInfo`: ANRs the system killed the app for (with the full thread dump), native crashes (every thread and build id from the tombstone on Android 12+, merged with the NDK record when there is one) and foreground low-memory kills. `enableExitInfoTracking(false)` turns it off.
- JVM crashes and watchdog ANRs list every thread, main thread first.
- Gradle plugin: `uploadBugCapsuleNativeSymbols<Variant>` uploads unstripped `.so` files (`kind=elf`) after `assemble`/`bundle`; `bugcapsule.uploadNativeSymbols` turns it off.
- Add `dev.bugcapsule.android.embedding.CrashBridge`: native crash, ANR and exit-record capture for SDKs that send events themselves (the Flutter SDK).

- Read capsule format v2 (interaction dependencies) as well as v1; write v2 only when a capsule has WebSocket or adapter traffic. Readers reject versions newer than 2.
- Add OkHttp WebSocket capture and offline replay (`BugCapsuleWebSockets`, adapter `bugcapsule-okhttp.websocket`).
- Add the native SDK adapter API (`NativeAdapter`, `NativeInteractions`) and `NativeAdapterConformance` in `bugcapsule-android-replay` to test adapters in unit tests.
- Replay ends `incompatible` (`dependency.unsupported`) when a required adapter is missing; interaction divergences (`ws.*`, `native.*`, `dependency.live`) keep `verify` from reporting a fix.
- The project's capture settings can turn off a single adapter without affecting HTTP capture.
- Replay fails every WebSocket and native adapter operation without a recording, including when the capsule has no recording for that adapter and for diagnostic adapters; it never falls back to the real dependency.
- Native operation keys are scoped by SDK instance (`<operation>@<instance>`) when the wrapper names one.

## 0.4.1-beta.4 — 2026-10-25

- Record soft-keyboard visibility with pointer input and restore it before injection. This prevents touches hitting the IME window on older Android versions; synchronization failures remain replay divergences.
- Register first-draw callbacks after the window attaches on older Android versions. This restores the replay session time origin and initial-display measurements on Android 6.
- Add first-draw lifecycle regression coverage for API 23 and API 28.

## 0.4.1-beta.3 — 2026-10-25

- Start JankStats frame tracking after Activity creation has completed, when the window has a DecorView. This restores frame measurements on activity and navigation transactions.
- Add a lifecycle regression test that verifies frame measurements reach the uploaded transaction envelope.

## 0.4.1-beta.2 — 2026-10-25

- Add single SDK token setup and connection status diagnostics.
- Report missing replay instrumentation and stop uploads when credentials are rejected.
- Update developer setup and integration documentation.
