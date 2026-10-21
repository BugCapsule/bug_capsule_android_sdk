package dev.bugcapsule.android.monitoring

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

    private fun tracker(file: File = File(tmp.root, "session.json")) = SessionTracker(
        file, "install-1", "app@1+1", "qa", 30_000, { hub.now }, { user },
        { p, sync -> sent.add(p to sync) }, hub::schedule,
    )

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
}
