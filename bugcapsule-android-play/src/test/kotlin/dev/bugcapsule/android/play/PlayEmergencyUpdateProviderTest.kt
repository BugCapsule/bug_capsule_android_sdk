package dev.bugcapsule.android.play

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import dev.bugcapsule.android.emergency.EmergencyAction
import dev.bugcapsule.android.emergency.EmergencyConfig
import dev.bugcapsule.android.emergency.EmergencyUpdateAvailability
import dev.bugcapsule.android.emergency.EmergencyUpdateFlow
import dev.bugcapsule.android.emergency.EmergencyUpdateRequest
import dev.bugcapsule.android.emergency.EmergencyUpdateState
import dev.bugcapsule.android.emergency.EmergencyUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/** The Play provider against the library's own FakeAppUpdateManager (emergency-client-v1 §7.7). */
@RunWith(RobolectricTestRunner::class)
class PlayEmergencyUpdateProviderTest {
    /** Records the update types Play was asked to start; [forceStart] makes the fake accept any start. */
    private class RecordingFake(app: Application) : FakeAppUpdateManager(app) {
        val starts = CopyOnWriteArrayList<Int>()

        @Volatile
        var forceStart = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            launcher: ActivityResultLauncher<IntentSenderRequest>,
            options: AppUpdateOptions,
        ): Boolean {
            starts += options.appUpdateType()
            return super.startUpdateFlowForResult(info, launcher, options) || forceStart
        }
    }

    /** Stands in for a launcher the app registered; the test passes its results to onUpdateFlowResult. */
    private class AppLauncher : ActivityResultLauncher<IntentSenderRequest>() {
        override val contract: ActivityResultContract<IntentSenderRequest, *> = ActivityResultContracts.StartIntentSenderForResult()

        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {}

        override fun unregister() {}
    }

    private lateinit var app: Application
    private lateinit var fake: RecordingFake
    private val statuses = CopyOnWriteArrayList<EmergencyUpdateStatus>()

    private val urgent = EmergencyUpdateRequest("2.4.2+57", EmergencyAction.REQUIRE_UPDATE, "P0")
    private val routine = EmergencyUpdateRequest("2.4.2+57", EmergencyAction.REQUIRE_UPDATE, "P2")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = RecordingFake(app)
    }

    private fun provider(configure: PlayEmergencyUpdateProvider.Builder.() -> Unit = {}): PlayEmergencyUpdateProvider =
        PlayEmergencyUpdateProvider.builder(app).appUpdateManager(fake).apply(configure).build().also { p ->
            p.setListener { statuses += it }
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun check(p: PlayEmergencyUpdateProvider, request: EmergencyUpdateRequest): EmergencyUpdateAvailability {
        val answers = CopyOnWriteArrayList<EmergencyUpdateAvailability>()
        p.checkAvailability(request) { answers += it }
        idle()
        return answers.single()
    }

    private fun state(p: PlayEmergencyUpdateProvider) = p.status()?.state

    // -------------------------------------------------------------- flexible

    @Test
    fun flexibleIsTheDefaultAndCompletesOnlyWhenTheAppAsks() {
        fake.setUpdateAvailable(57)
        val p = provider()
        val a = check(p, urgent)
        assertTrue(a.available)
        assertEquals(setOf(EmergencyUpdateFlow.FLEXIBLE, EmergencyUpdateFlow.IMMEDIATE), a.allowedFlows)
        assertEquals("not opted in to immediate updates", EmergencyUpdateFlow.FLEXIBLE, a.flow)
        assertEquals("2.4.2+57", a.availableVersion)
        assertEquals(EmergencyUpdateState.AVAILABLE, state(p))
        assertFalse("nothing to complete yet", p.completeUpdate())

        p.startUpdate(activity(), urgent)
        idle()
        assertEquals(listOf(AppUpdateType.FLEXIBLE), fake.starts)
        assertTrue(fake.isConfirmationDialogVisible)
        assertEquals(EmergencyUpdateFlow.FLEXIBLE, p.status()?.flow)

        fake.userAcceptsUpdate()
        idle()
        assertEquals(EmergencyUpdateState.IN_PROGRESS, state(p))
        fake.downloadStarts()
        fake.setTotalBytesToDownload(1_000)
        fake.setBytesDownloaded(400)
        idle()
        assertEquals(EmergencyUpdateState.IN_PROGRESS, state(p))
        assertEquals(400L, p.status()?.bytesDownloaded)
        assertEquals(1_000L, p.status()?.totalBytesToDownload)
        fake.downloadCompletes()
        idle()
        assertEquals(EmergencyUpdateState.DOWNLOADED, state(p))
        assertFalse("never installed by itself", fake.isInstallSplashScreenVisible)

        p.onUpdateFlowResult(Activity.RESULT_OK)
        assertEquals("a late RESULT_OK keeps the download", EmergencyUpdateState.DOWNLOADED, state(p))

        assertTrue(p.completeUpdate())
        idle()
        assertTrue(fake.isInstallSplashScreenVisible)
        assertEquals(
            listOf("available", "in_progress", "downloaded"),
            statuses.map { it.state.wire }.distinct(),
        )
        assertTrue(statuses.all { it.provider == "google_play" && it.requiredVersion == "2.4.2+57" })
    }

    // ------------------------------------------------------------- immediate

    @Test
    fun immediateOnlyWhenOptedInUrgentAndAllowedByPlay() {
        fun flowFor(allowImmediate: Boolean, request: EmergencyUpdateRequest, vararg playTypes: Int): EmergencyUpdateFlow? {
            fake = RecordingFake(app)
            if (playTypes.size == 1) fake.setUpdateAvailable(57, playTypes[0]) else fake.setUpdateAvailable(57)
            return check(provider { allowImmediateUpdates(allowImmediate) }, request).flow
        }
        assertEquals(EmergencyUpdateFlow.IMMEDIATE, flowFor(true, urgent))
        assertEquals(EmergencyUpdateFlow.IMMEDIATE, flowFor(true, EmergencyUpdateRequest("2.4.2+57", EmergencyAction.REQUIRE_UPDATE, "P1")))
        assertEquals("not opted in", EmergencyUpdateFlow.FLEXIBLE, flowFor(false, urgent))
        assertEquals("severity P2", EmergencyUpdateFlow.FLEXIBLE, flowFor(true, routine))
        assertEquals(
            "not REQUIRE_UPDATE",
            EmergencyUpdateFlow.FLEXIBLE,
            flowFor(true, EmergencyUpdateRequest("2.4.2+57", EmergencyAction.BLOCK, "P0")),
        )
        assertEquals("Play allows only flexible", EmergencyUpdateFlow.FLEXIBLE, flowFor(true, urgent, AppUpdateType.FLEXIBLE))

        fake = RecordingFake(app)
        fake.setUpdateAvailable(57)
        val p = provider { allowImmediateUpdates(true) }
        p.startUpdate(activity(), urgent)
        idle()
        assertEquals(listOf(AppUpdateType.IMMEDIATE), fake.starts)
        assertTrue(fake.isImmediateFlowVisible)
        assertEquals(EmergencyUpdateFlow.IMMEDIATE, p.status()?.flow)
        assertEquals(EmergencyUpdateState.AVAILABLE, state(p))
    }

    @Test
    fun aFlowPlayDoesNotAllowFails() {
        fake.setUpdateAvailable(57, AppUpdateType.IMMEDIATE)
        val p = provider()
        p.startUpdate(activity(), routine)
        idle()
        assertEquals(listOf(AppUpdateType.FLEXIBLE), fake.starts)
        assertEquals(EmergencyUpdateState.FAILED, state(p))
    }

    @Test
    fun resumeRestartsAnInterruptedImmediateUpdateOnly() {
        fake.setUpdateAvailable(57)
        val p = provider { allowImmediateUpdates(true) }
        val activity = activity()
        p.startUpdate(activity, urgent)
        idle()
        fake.userAcceptsUpdate() // Play now reports DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        fake.forceStart = true
        p.resumeUpdate(activity)
        idle()
        assertEquals(listOf(AppUpdateType.IMMEDIATE, AppUpdateType.IMMEDIATE), fake.starts)
        assertEquals(EmergencyUpdateState.IN_PROGRESS, state(p))

        // A flexible download in progress is reported, not turned into an immediate flow.
        fake = RecordingFake(app)
        fake.setUpdateAvailable(57)
        val q = provider()
        q.startUpdate(activity, routine)
        idle()
        fake.userAcceptsUpdate()
        q.resumeUpdate(activity)
        idle()
        assertEquals(listOf(AppUpdateType.FLEXIBLE), fake.starts)
        assertEquals(EmergencyUpdateState.IN_PROGRESS, state(q))
        fake.downloadStarts()
        fake.downloadCompletes()
        q.resumeUpdate(activity)
        idle()
        assertEquals(EmergencyUpdateState.DOWNLOADED, state(q))
        assertFalse(fake.isInstallSplashScreenVisible)
    }

    @Test
    fun resumeWithoutAFlowInThisProcessReportsADownloadedUpdate() {
        fake.setUpdateAvailable(57)
        val first = provider()
        first.startUpdate(activity(), routine)
        idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadCompletes()

        statuses.clear()
        val fresh = provider()
        fresh.resumeUpdate(activity())
        idle()
        assertEquals(EmergencyUpdateState.DOWNLOADED, state(fresh))
        assertNull("no request in this process", fresh.status()?.requiredVersion)
        assertEquals(1, fake.starts.size)
    }

    // ------------------------------------------------------- declined, failed

    @Test
    fun activityResultsBecomeStates() {
        fake.setUpdateAvailable(57)
        val p = provider()
        val launcher = AppLauncher()
        p.startUpdate(launcher, routine)
        idle()
        assertEquals(listOf(AppUpdateType.FLEXIBLE), fake.starts)
        fake.userRejectsUpdate()
        p.onUpdateFlowResult(Activity.RESULT_CANCELED)
        assertEquals(EmergencyUpdateState.DECLINED, state(p))

        p.startUpdate(launcher, routine)
        idle()
        p.onUpdateFlowResult(Activity.RESULT_OK)
        assertEquals(EmergencyUpdateState.IN_PROGRESS, state(p))
        p.onUpdateFlowResult(ActivityResult.RESULT_IN_APP_UPDATE_FAILED)
        assertEquals(EmergencyUpdateState.FAILED, state(p))
    }

    @Test
    fun cancelledAndFailedDownloads() {
        fake.setUpdateAvailable(57)
        val p = provider()
        p.startUpdate(AppLauncher(), routine)
        idle()
        fake.userAcceptsUpdate()
        fake.userCancelsDownload()
        idle()
        assertEquals(EmergencyUpdateState.DECLINED, state(p))

        p.startUpdate(AppLauncher(), routine)
        idle()
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.downloadFails()
        idle()
        assertEquals(EmergencyUpdateState.FAILED, state(p))
        assertFalse(p.completeUpdate())
    }

    @Test
    fun aFailedCheckIsReportedWithItsError() {
        fake.setInstallErrorCode(InstallErrorCode.ERROR_APP_NOT_OWNED)
        val p = provider()
        val a = check(p, routine)
        assertFalse(a.available)
        assertEquals("Play In-App Updates error ${InstallErrorCode.ERROR_APP_NOT_OWNED}", a.error)
        assertEquals(EmergencyUpdateState.FAILED, state(p))
        p.startUpdate(activity(), routine)
        idle()
        assertTrue(fake.starts.isEmpty())
    }

    @Test
    fun aPlainActivityCannotStartAFlow() {
        fake.setUpdateAvailable(57)
        val p = provider()
        p.startUpdate(Robolectric.buildActivity(Activity::class.java).setup().get(), routine)
        idle()
        assertTrue(fake.starts.isEmpty())
        assertEquals(EmergencyUpdateState.FAILED, state(p))
    }

    // --------------------------------------------------------- version codes

    @Test
    fun requiredVersionsBecomeVersionCodes() {
        val p = provider { versionCode("2.5.0", 60).versionCode("2.4.2", 58) }
        assertEquals("build number", 57, p.requiredVersionCode("2.4.1+57"))
        assertEquals("mapping", 60, p.requiredVersionCode("2.5.0"))
        assertEquals("mapping wins over the build number", 58, p.requiredVersionCode("2.4.2+57"))
        assertNull(p.requiredVersionCode("2.6.0"))
        assertNull(p.requiredVersionCode("2.6.0+rc1"))
        assertNull(p.requiredVersionCode(null))
        assertNull(p.requiredVersionCode(" "))
        try {
            PlayEmergencyUpdateProvider.builder(app).versionCode("1.0", 0)
            fail("version code 0 accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun anUpdateIsAvailableOnlyFromTheRequiredVersionCode() {
        fun available(playCode: Int, required: String?, configure: PlayEmergencyUpdateProvider.Builder.() -> Unit = {}): EmergencyUpdateAvailability {
            fake = RecordingFake(app)
            fake.setUpdateAvailable(playCode)
            return check(provider(configure), EmergencyUpdateRequest(required, EmergencyAction.REQUIRE_UPDATE, "P2"))
        }
        assertFalse(available(56, "2.4.2+57").available)
        assertTrue(available(57, "2.4.2+57").available)
        assertTrue(available(58, "2.4.2+57").available)
        assertFalse(available(57, "2.5.0") { versionCode("2.5.0", 60) }.available)
        assertTrue(available(60, "2.5.0") { versionCode("2.5.0", 60) }.available)
        assertEquals("named by the mapping", "2.5.0", available(60, "2.5.0") { versionCode("2.5.0", 60) }.availableVersion)
        assertNull("Play reports codes only", available(58, "2.4.2+57").availableVersion)
        assertTrue("no code: any newer update counts", available(2, "2.6.0").available)
        assertTrue(available(2, null).available)

        val older = available(56, "2.4.2+57")
        assertNull(older.flow)
        assertTrue(older.allowedFlows.isEmpty())
        assertEquals("2.4.2+57", older.requiredVersion)

        fake = RecordingFake(app)
        fake.setUpdateNotAvailable()
        val p = provider()
        assertFalse(check(p, routine).available)
        assertEquals(EmergencyUpdateState.NOT_AVAILABLE, state(p))
        p.startUpdate(activity(), routine)
        idle()
        assertTrue(fake.starts.isEmpty())
        assertEquals(EmergencyUpdateState.NOT_AVAILABLE, state(p))
    }

    @Test
    fun stalenessAndPriorityComeFromPlay() {
        fake.setUpdateAvailable(57)
        fake.setClientVersionStalenessDays(9)
        fake.setUpdatePriority(4)
        val a = check(provider(), routine)
        assertEquals(9, a.stalenessDays)
        assertEquals(4, a.priority)
    }

    @Test
    fun itIsAValidEmergencyUpdateProvider() {
        val p = provider()
        assertEquals("google_play", p.provider)
        assertEquals(p, EmergencyConfig.builder().updateProvider(p).build().updateProvider)
        assertFalse(p.immediateUpdatesAllowed)
        assertTrue(provider { allowImmediateUpdates(true) }.immediateUpdatesAllowed)
    }
}
