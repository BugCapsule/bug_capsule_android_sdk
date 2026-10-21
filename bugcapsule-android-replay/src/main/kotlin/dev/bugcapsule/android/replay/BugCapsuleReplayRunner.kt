package dev.bugcapsule.android.replay

import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import dev.bugcapsule.android.core.ReplayProtocol

/**
 * Instrumentation runner that arms a BugCapsule replay. Use it as the app's
 * `testInstrumentationRunner`; without the `bugcapsuleReplayUrl` argument it
 * is a plain [AndroidJUnitRunner].
 *
 * The CLI runs `am instrument -r -w -e bugcapsuleReplayUrl <url> -e class
 * dev.bugcapsule.android.replay.BugCapsuleReplayTest <test package>/<this runner>`.
 * [onCreate] runs before `Application.onCreate`: it fetches the capsule,
 * restores SharedPreferences/DataStore, and refuses unless the target app is
 * debuggable. Results are also emitted as instrumentation status
 * `bugcapsule_result` = `BUGCAPSULE_RESULT::{json}`.
 */
open class BugCapsuleReplayRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val url = arguments?.getString(ReplayProtocol.INSTRUMENTATION_ARG)
        if (!url.isNullOrEmpty()) {
            try {
                BugCapsuleReplay.arm(this, url) { line ->
                    sendStatus(STATUS_CODE, Bundle().apply { putString(ReplayProtocol.STATUS_KEY, line) })
                }
            } catch (e: Throwable) {
                Log.w("BugCapsule", "could not arm replay: $e")
            }
        }
        super.onCreate(arguments)
    }

    companion object {
        /** Instrumentation status code of the `bugcapsule_result` bundle. */
        const val STATUS_CODE = 7
    }
}
