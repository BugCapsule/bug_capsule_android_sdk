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
 *
 * Crash capsules: when an attempt's process dies of a native signal or is
 * killed, it cannot report. The CLI then runs the same instrumentation with
 * `-e bugcapsuleReplayPostMortem true -e class
 * dev.bugcapsule.android.replay.BugCapsuleReplayTest#judgeProcessDeath`, which
 * reports that attempt from the system's exit record (docs/how-it-works.md).
 */
open class BugCapsuleReplayRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val url = arguments?.getString(ReplayProtocol.INSTRUMENTATION_ARG)
        if (!url.isNullOrEmpty()) {
            val sink = StatusSink { line -> sendStatus(STATUS_CODE, Bundle().apply { putString(ReplayProtocol.STATUS_KEY, line) }) }
            try {
                if (arguments.getString(POST_MORTEM_ARG) == "true") {
                    BugCapsuleReplay.armPostMortem(this, url, sink)
                } else {
                    BugCapsuleReplay.arm(this, url, sink)
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

        /**
         * `-e bugcapsuleReplayPostMortem true`: judge how the previous attempt's
         * process died instead of replaying (crash capsules; run with
         * `-e class dev.bugcapsule.android.replay.BugCapsuleReplayTest#judgeProcessDeath`).
         */
        const val POST_MORTEM_ARG = "bugcapsuleReplayPostMortem"
    }
}
