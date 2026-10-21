package dev.bugcapsule.android.replay

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The test the CLI runs for every replay attempt (`-e class
 * dev.bugcapsule.android.replay.BugCapsuleReplayTest`). Skipped unless
 * [BugCapsuleReplayRunner] armed a replay.
 *
 * Launches the app's launcher activity with [ActivityScenario], waits for
 * `BugCapsule.init` (in replay mode) and the first drawn frame, then drives
 * the in-app engine on this thread. The engine reports over HTTP and as
 * instrumentation status; a replayed crash reports from the crash handler.
 */
@RunWith(AndroidJUnit4::class)
class BugCapsuleReplayTest {
    @Test
    fun replayCapsule() {
        assumeTrue("no BugCapsule replay armed (started by `bugcapsule run`)", BugCapsuleReplay.isArmed())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            BugCapsuleReplay.reportError("the app under test has no launcher activity")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val driver = InstrumentationReplayDriver(instrumentation)
        val scenario = try {
            ActivityScenario.launch<Activity>(intent)
        } catch (e: Throwable) {
            BugCapsuleReplay.reportError("could not launch ${intent.component}: $e")
            return
        }
        driver.scenario = scenario
        try {
            val engine = BugCapsuleReplay.awaitEngine(ENGINE_TIMEOUT_MS)
            if (engine == null) {
                if (!BugCapsuleReplay.initialized()) {
                    BugCapsuleReplay.reportError("BugCapsule.init was not called in Application.onCreate")
                }
                return // init reported already (e.g. incompatible state schema)
            }
            if (!BugCapsuleReplay.awaitAnchor(ENGINE_TIMEOUT_MS)) {
                Log.w("BugCapsule", "no frame drawn within ${ENGINE_TIMEOUT_MS}ms; replaying anyway")
            }
            val report = engine.run(driver)
            Log.i("BugCapsule", "replay finished: ${report.outcome.wire}")
        } finally {
            try {
                scenario.close()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val ENGINE_TIMEOUT_MS = 20_000L
    }
}
