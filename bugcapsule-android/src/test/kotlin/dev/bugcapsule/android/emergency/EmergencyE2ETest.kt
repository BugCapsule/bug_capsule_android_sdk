package dev.bugcapsule.android.emergency

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Opt-in end-to-end check against a real backend. Skipped unless
 * `BUGCAPSULE_EMERGENCY_E2E_URL` is set, together with
 * `BUGCAPSULE_EMERGENCY_E2E_KEY` (ingest key), `_ANCHOR`, `_ENV`, `_FEATURE`
 * and `_EXPECT_ACTION`:
 *
 * ```
 * BUGCAPSULE_EMERGENCY_E2E_URL=https://api.example.com BUGCAPSULE_EMERGENCY_E2E_KEY=bct_… \
 * BUGCAPSULE_EMERGENCY_E2E_ANCHOR=bcek.v1.… BUGCAPSULE_EMERGENCY_E2E_ENV=staging \
 * BUGCAPSULE_EMERGENCY_E2E_FEATURE=checkout.confirm_order BUGCAPSULE_EMERGENCY_E2E_EXPECT_ACTION=BLOCK \
 * ./gradlew :bugcapsule-android:testDebugUnitTest --tests '*EmergencyE2ETest*'
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class EmergencyE2ETest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = BugCapsule.resetForTest()

    private fun env(name: String): String? = System.getenv("BUGCAPSULE_EMERGENCY_E2E_$name")?.takeIf { it.isNotBlank() }

    @Test
    fun fetchesAndEvaluatesAgainstARealBackend() {
        val url = env("URL")
        assumeTrue("set BUGCAPSULE_EMERGENCY_E2E_URL (and _KEY, _ANCHOR, _ENV, _FEATURE, _EXPECT_ACTION) to run", url != null)
        fun required(name: String) = env(name) ?: throw AssertionError("BUGCAPSULE_EMERGENCY_E2E_$name is required with _URL")
        val app = ApplicationProvider.getApplicationContext<Application>()
        BugCapsule.resetForTest()
        BugCapsule.emergency.testHooks = EmergencyTestHooks(storageDir = tmp.root, foreground = false)
        BugCapsule.emergency.initialize(
            app,
            EmergencyConfig.builder()
                .trustAnchor(required("ANCHOR"))
                .environment(required("ENV"))
                .backend(url!!, required("KEY"))
                .build(),
        )
        val status = BugCapsule.emergency.refreshPolicies().get(30, TimeUnit.SECONDS)
        assertEquals("status: $status", EmergencyState.USABLE, status.state)
        val decision = BugCapsule.emergency.evaluate(required("FEATURE"))
        assertEquals("decision: $decision", EmergencyAction.valueOf(required("EXPECT_ACTION")), decision.action)
        assertEquals(EmergencyDecisionSource.POLICY, decision.source)
    }
}
