# Changelog

## Unreleased

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
