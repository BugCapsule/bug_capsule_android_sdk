# Changelog

## Unreleased

- Register first-draw callbacks after the window attaches on older Android versions. This restores the replay session time origin and initial-display measurements on Android 6.
- Add first-draw lifecycle regression coverage for API 23 and API 28.

## 0.4.1-beta.3 — 2026-10-25

- Start JankStats frame tracking after Activity creation has completed, when the window has a DecorView. This restores frame measurements on activity and navigation transactions.
- Add a lifecycle regression test that verifies frame measurements reach the uploaded transaction envelope.

## 0.4.1-beta.2 — 2026-10-25

- Add single SDK token setup and connection status diagnostics.
- Report missing replay instrumentation and stop uploads when credentials are rejected.
- Update developer setup and integration documentation.
