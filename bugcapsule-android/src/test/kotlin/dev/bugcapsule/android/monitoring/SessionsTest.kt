package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.PatchIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val hub = ManualHub()
    private val sent = ArrayList<Pair<Map<String, Any?>, Boolean>>()
    private var user: String? = null

    private fun tracker(file: File = File(tmp.root, "session.json"), release: String = "app@1+1", environment: String = "qa") = SessionTracker(
        file, "install-1", release, environment, 30_000, { hub.now }, { user },
        { p, sync -> sent.add(p to sync) }, hub::schedule,
    )

    private val patched = PatchIdentity("base-1", "sha256:patched", 1, "sha256:host")
    private val bare = PatchIdentity("base-1", null, 1, "sha256:host")

    private fun patchTracker(identity: PatchIdentity?) = SessionTracker(
        File(tmp.root, "session.json"), "install-1", "app@1+1", "production", 30_000, { hub.now }, { user },
        { p, sync -> sent.add(p to sync) }, hub::schedule, { identity },
    )

    @Test
    fun everyUpdateCarriesThePatchStartingWithTheFirst() {
        val t = patchTracker(patched)
        t.onForeground()
        t.end()

        assertEquals(2, sent.size)
        sent.forEach { (payload, _) ->
            assertEquals("base-1", payload["patchBaseBuildId"])
            assertEquals("sha256:patched", payload["patchSetDigest"])
        }
    }

    @Test
    fun theBareBaseReportsItsBuildWithNoDigestTheRolloutControl() {
        patchTracker(bare).onForeground()

        assertEquals("base-1", sent.single().first["patchBaseBuildId"])
        assertFalse(sent.single().first.containsKey("patchSetDigest"))
    }

    @Test
    fun noPatchRuntimeSendsNeitherField() {
        patchTracker(null).onForeground()

        assertFalse(sent.single().first.containsKey("patchBaseBuildId"))
        assertFalse(sent.single().first.containsKey("patchSetDigest"))
    }

    @Test
    fun aRecoveredSessionReportsThePatchItRanNotTheCurrentOne() {
        patchTracker(patched).onForeground()
        sent.clear()

        patchTracker(bare).recoverPrevious(hub.now)

        assertEquals("crashed", sent.single().first["status"])
        assertEquals("base-1", sent.single().first["patchBaseBuildId"])
        assertEquals("sha256:patched", sent.single().first["patchSetDigest"])
    }

    @Test
    fun aSessionSavedBeforeIdentityExistedClaimsNoPatch() {
        patchTracker(null).onForeground()
        sent.clear()

        patchTracker(patched).recoverPrevious(hub.now)

        assertFalse(sent.single().first.containsKey("patchBaseBuildId"))
        assertFalse(sent.single().first.containsKey("patchSetDigest"))
    }

    @Test
    fun aForegroundRunIsOneSessionEndedThirtySecondsIntoTheBackground() {
        val t = tracker()
        t.onForeground()
        assertEquals(true, sent[0].first["init"])
        assertEquals("ok", sent[0].first["status"])
        assertEquals("install-1", sent[0].first["did"])
        val sid = sent[0].first["sid"]
        hub.advance(10_000)
        t.onBackground()
        hub.advance(5_000)
        t.onForeground() // back within the timeout: same session
        assertEquals(1, sent.size)
        hub.advance(20_000)
        t.onBackground()
        hub.advance(30_000)
        assertEquals(2, sent.size)
        val end = sent[1].first
        assertEquals(sid, end["sid"])
        assertEquals("exited", end["status"])
        assertEquals(false, end["init"])
        assertEquals(35.0, end["duration"])
        assertTrue((end["seq"] as Long) > (sent[0].first["seq"] as Long))
        assertFalse(t.isActive)
    }

    @Test
    fun firstErrorAndCrashAreSentWithTheEvent() {
        val t = tracker()
        user = "u_1"
        t.onForeground()
        assertEquals("u_1", sent[0].first["did"])
        val first = t.onError(fatal = false)!!
        assertEquals(1, first["errors"])
        assertNull("later errors wait for the end", t.onError(fatal = false))
        val crash = t.onError(fatal = true)!!
        assertEquals("crashed", crash["status"])
        assertEquals(3, crash["errors"])
        assertFalse(t.isActive)
        assertFalse(File(tmp.root, "session.json").exists())
    }

    @Test
    fun aSessionLeftInTheForegroundIsAbnormalOnTheNextLaunch() {
        tracker().onForeground()
        sent.clear()
        hub.advance(4_000)
        tracker().recoverPrevious(crashedAtMs = null)
        assertEquals("abnormal", sent.single().first["status"])
    }

    @Test
    fun aSessionLeftInTheBackgroundExitedWhenItWentThere() {
        val t = tracker()
        t.onForeground()
        hub.advance(7_000)
        t.onBackground()
        sent.clear()
        tracker().recoverPrevious(crashedAtMs = null)
        assertEquals("exited", sent.single().first["status"])
        assertEquals(7.0, sent.single().first["duration"])
    }

    @Test
    fun aNativeCrashMarksThePreviousSessionCrashed() {
        tracker().onForeground()
        sent.clear()
        tracker().recoverPrevious(crashedAtMs = hub.now + 2_000)
        assertEquals("crashed", sent.single().first["status"])
        assertEquals(1, sent.single().first["errors"])
        sent.clear()
        tracker().recoverPrevious(null)
        assertTrue("recovered once", sent.isEmpty())
    }

    @Test
    fun aRecoveredSessionKeepsTheReleaseItRanWithAfterAnUpdate() {
        tracker(release = "app@1+1", environment = "production").onForeground()
        sent.clear()
        tracker(release = "app@2+2", environment = "staging").recoverPrevious(crashedAtMs = hub.now + 1_000)
        val recovered = sent.single().first
        assertEquals("crashed", recovered["status"])
        assertEquals("app@1+1", recovered["release"])
        assertEquals("production", recovered["environment"])
    }

    @Test
    fun aSessionFileWithoutReleaseFallsBackToTheCurrentOne() {
        val file = File(tmp.root, "session.json")
        file.writeText("""{"sid":"b3a1c2d4-0000-4000-8000-000000000001","did":"d","started":1,"seq":2,"status":"ok","errors":0,"timestamp":5}""")
        tracker(release = "app@2+2").recoverPrevious(null)
        assertEquals("abnormal", sent.single().first["status"])
        assertEquals("app@2+2", sent.single().first["release"])
    }
}
