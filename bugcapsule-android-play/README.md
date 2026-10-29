# bugcapsule-android-play

Google Play In-App Updates for BugCapsule emergency recovery. When an
emergency policy says `REQUIRE_UPDATE`, this module offers the user the Play
build that fixes the problem, and reports how far the update got.

**For apps distributed through Google Play only.** Play In-App Updates work
only for an app installed from Google Play. If you distribute the app another
way (another store, enterprise MDM, direct download), implement
`EmergencyUpdateProvider` from `bugcapsule-android` for that channel instead,
for example by opening your store's product page. The SDK never downloads or
installs a package by itself.

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.bugcapsule:bugcapsule-android:0.4.1-beta.6")
    implementation("io.github.bugcapsule:bugcapsule-android-play:0.4.1-beta.6")
}
```

It brings `com.google.android.play:app-update` 2.1.0 and `androidx.activity`.

## Set up

```kotlin
// Application.onCreate
BugCapsule.emergency.initialize(this, EmergencyConfig.builder()
    .trustAnchor(BuildConfig.BUGCAPSULE_EMERGENCY_ANCHOR)
    .updateProvider(PlayEmergencyUpdateProvider.builder(this)
        .allowImmediateUpdates(true)   // optional, see below
        .versionCode("2.4.2", 57)      // optional, see below
        .build())
    .build())
```

```kotlin
// In an AppCompatActivity / FragmentActivity / ComponentActivity
val decision = BugCapsule.emergency.evaluate("checkout.confirm_order")
if (decision.action == EmergencyAction.REQUIRE_UPDATE) {
    BugCapsule.emergency.updates.startUpdate(this, decision)
}

BugCapsule.emergency.updates.addListener { status ->
    if (status.state == EmergencyUpdateState.DOWNLOADED) {
        showRestartPrompt { BugCapsule.emergency.updates.completeUpdate() }  // restarts the app
    }
}

override fun onResume() {
    super.onResume()
    BugCapsule.emergency.updates.resumeUpdate(this)
}
```

Java:

```java
BugCapsule.getEmergency().getUpdates().startUpdate(activity, decision);
```

`checkForUpdate(decision, callback)` asks Play without starting anything; the
callback gets an `EmergencyUpdateAvailability` with the allowed flows, the
flow `startUpdate` would use, staleness days and Play's update priority.
Callbacks and listeners run on the main thread.

## Which flow

- **Flexible** is the default. The update downloads in the background and the
  user keeps using the app. When it is `DOWNLOADED`, ask the user and call
  `completeUpdate()`. The SDK never calls it by itself, because installing
  restarts the app.
- **Immediate** (full screen, the user has to update or cancel) is used only
  when all three hold:
  1. the app opted in with `allowImmediateUpdates(true)`;
  2. the decision is `REQUIRE_UPDATE` with severity `P0` or `P1`;
  3. Play allows an immediate update for that update.

  If the immediate flow is interrupted (the user leaves the app),
  `resumeUpdate(activity)` from `onResume` starts it again. A flexible
  download in progress is reported, never switched to immediate on resume.

## Required version and version codes

A policy names a `required_version`; Play reports version codes. An update
counts as available only when Play's version code is at least the required
one, which is found in this order:

1. your mapping, `versionCode("2.4.2", 57)` (matched with or without `+build`);
2. the build number of the required version: `2.4.2+57` → 57.

Without either, any Play update newer than the installed build counts.
`availableVersion` in statuses is the version name only when your mapping or
the required version names that code; otherwise it is null.

## States

| Play | Reported state |
|---|---|
| no update at least the required version | `not_available` |
| update available | `available` |
| activity result `RESULT_OK`; install `PENDING`, `DOWNLOADING`, `INSTALLING` | `in_progress` |
| install `DOWNLOADED` | `downloaded` |
| activity result `RESULT_CANCELED`; install `CANCELED` | `declined` |
| activity result `RESULT_IN_APP_UPDATE_FAILED`; install `FAILED`; a failed Play call | `failed` |

Each change also goes to the backend as a runtime report (provider
`google_play`, flow, state, available and required version), at most one
every 60 s, never while a capsule is replayed. An installed update can't be
observed by the process that started it: it shows up as the new app version
in the next receipt.

## Activities and results

`startUpdate(activity, …)` needs a `ComponentActivity`: the provider registers
a one-shot launcher on its `ActivityResultRegistry`. If the activity is
recreated while Play's screen is up, that result is lost; the next
`checkForUpdate` or `resumeUpdate` reads the state from Play again. To keep
results across recreation, register the launcher yourself and pass results
back:

```kotlin
// `playUpdates` is the PlayEmergencyUpdateProvider you passed to EmergencyConfig, kept by your Application.
private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
    playUpdates.onUpdateFlowResult(it.resultCode)
}

playUpdates.startUpdate(updateLauncher, EmergencyUpdateRequest.from(decision))
playUpdates.resumeUpdate(updateLauncher)   // in onResume
```

## Testing

Pass Play's `FakeAppUpdateManager` (`com.google.android.play.core.appupdate.testing`)
with `appUpdateManager(fake)` to drive the provider in unit tests.

## Not supported

The module uses only the Play In-App Updates API. It does not install APKs,
load downloaded DEX/JAR files or native libraries, or do anything else that
bypasses Google Play's policies.
