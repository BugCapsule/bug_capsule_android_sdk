# Working on the Android SDK

For maintainers of this repository. App developers want the
[README](../README.md) instead.

## Build and test

```bash
./gradlew test                                   # every module, plus the Gradle plugin's tests
./gradlew :demo:connectedDebugAndroidTest        # emulator; unset BUGCAPSULE_SDK_TOKEN / BUGCAPSULE_INGEST_KEY first
bugcapsule-ndk/src/test/native/smoke.sh          # host smoke test of the signal handler (clang)
```

Monitoring is tested against a stubbed server (MockWebServer): envelopes,
capsule links, sessions, sampling, remote config, `Retry-After`, trace
propagation, feedback. Token-only setup is tested the same way
(`TokenSetupTest`: config fetch with the token's credential, explicit backend
precedence, invalid / unsupported tokens, revoked token, hanging network,
offline outbox), and the parser runs every shared vector in
`spec-fixtures/connection-token-v1/vectors.json` (`ConnectionTokenTest`).

The Gradle plugin is an included build (`bugcapsule-gradle-plugin/`), which
the demo uses through `pluginManagement { includeBuild(...) }`.

## Shared fixtures

`spec-fixtures/` must equal the Flutter SDK's `spec/fixtures`
(`FixtureSyncTest`). Re-sync with `tool/sync_spec.sh` in
[bug_capsule_flutter_sdk](https://github.com/BugCapsule/bug_capsule_flutter_sdk),
with both repositories checked out side by side. The script does not yet copy
`capsule-v2`; copy `spec/fixtures/capsule-v2` next to `capsule-v1` by hand.
`InteractionConformanceTest` and `ConformanceTest.verdicts` run
`conformance/interactions.json` and `conformance/verdicts.json`.

## Release procedure

1. Bump `VERSION_NAME` in `gradle.properties` (`SDK_VERSION` in BuildConfig
   follows).
2. `./gradlew clean publishAll` writes AARs, sources jars and POMs to
   `build/repo/` (a local Maven repository for review). Point a consuming
   project at it with `maven { url = uri("<path>/build/repo") }`.
3. Run the unit tests, `:demo:connectedDebugAndroidTest`, and the CLI
   end-to-end run (`bugcapsule run/verify/regress`) on an emulator.
4. `scripts/central-bundle.py --sign-key <key id>` builds a signed Maven
   Central bundle locally (it never uploads). Upload it to the Central Portal
   under `io.github.bugcapsule`.

Validation evidence for past releases: [release-readiness.md](release-readiness.md).
