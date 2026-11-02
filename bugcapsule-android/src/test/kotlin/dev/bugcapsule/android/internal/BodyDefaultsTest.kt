package dev.bugcapsule.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** Network bodies: off in production unless the app asks, on in debug/QA; remote config only turns them off (BC-ENT-017). */
@RunWith(RobolectricTestRunner::class)
class BodyDefaultsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    private fun bodies(mode: CaptureMode, explicit: Boolean?, remote: Boolean? = null): Boolean {
        BugCapsule.resetForTest()
        val transport = FakeTransport()
        if (remote != null) transport.lastConfig = ConfigFetchResult.Fetched(FakeTransport.config(bodies = remote))
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.newFolder(), if (remote != null) transport else null)
        val b = BugCapsuleConfig.builder().mode(mode).productionSampleRate(1.0).captureNetworkBodies(explicit)
        if (remote != null) b.backend("https://backend.test", "bci_test_key_000000000000000")
        BugCapsule.init(app, b.build())
        if (remote != null) BugCapsule.refreshRemoteConfig().get(5, TimeUnit.SECONDS)
        return BugCapsule.session!!.captureNetworkBodies
    }

    @Test
    fun theDefaultFollowsTheMode() {
        assertEquals(false, bodies(CaptureMode.PRODUCTION, null))
        assertEquals(true, bodies(CaptureMode.QA, null))
        assertEquals(true, bodies(CaptureMode.DEBUG, null))
        assertEquals(null, BugCapsuleConfig.builder().build().captureNetworkBodies)
    }

    @Test
    fun anExplicitSettingAlwaysWins() {
        assertEquals(true, bodies(CaptureMode.PRODUCTION, true))
        assertEquals(false, bodies(CaptureMode.QA, false))
        assertEquals(false, bodies(CaptureMode.DEBUG, false))
    }

    @Test
    fun remoteConfigCanOnlyTurnBodiesOff() {
        assertEquals(false, bodies(CaptureMode.PRODUCTION, null, remote = true))
        assertEquals(false, bodies(CaptureMode.QA, false, remote = true))
        assertEquals(false, bodies(CaptureMode.QA, null, remote = false))
        assertEquals(false, bodies(CaptureMode.PRODUCTION, true, remote = false))
        assertEquals(true, bodies(CaptureMode.PRODUCTION, true, remote = true))
    }
}
