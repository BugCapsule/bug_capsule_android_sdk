package dev.bugcapsule.demo

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.storage.BugCapsuleDataStore
import dev.bugcapsule.android.storage.BugCapsuleSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * BC-ENT-036 on a device: `BugCapsule.init` on the main thread raises no
 * StrictMode disk read or write violation from the SDK. The Robolectric
 * `StartupTest` checks the same with a SecurityManager.
 */
@RunWith(AndroidJUnit4::class)
class StartupStrictModeTest {
    @Test
    fun initDoesNoDiskIoOnTheMainThread() {
        assumeTrue("StrictMode listeners need API 28", Build.VERSION.SDK_INT >= 28)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val violations = Collections.synchronizedList(ArrayList<String>())
        var previous: StrictMode.ThreadPolicy? = null
        BugCapsule.resetForTest()
        instrumentation.runOnMainSync {
            previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyListener({ it.run() }) { v ->
                        val stack = v.stackTrace.joinToString("\n") { "  at $it" }
                        if (stack.contains("dev.bugcapsule.android")) violations.add("$v\n$stack")
                    }
                    .build(),
            )
            BugCapsule.init(
                app,
                BugCapsuleConfig.builder().mode(CaptureMode.QA).redactKeys("loyaltyId").tracesSampleRate(1.0).build(),
                listOf(
                    ShopApp.okHttp,
                    ShopApp.navigation,
                    BugCapsuleSharedPreferences.builder().allowFile(CartCache.FILE).build(),
                    BugCapsuleDataStore.builder().allow(SettingsStore.NAME).build(),
                ),
            )
        }
        // Thread violations are delivered after the main thread's current message.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { StrictMode.setThreadPolicy(previous) }

        assertEquals("disk I/O by the SDK on the main thread:\n" + violations.joinToString("\n\n"), 0, violations.size)
        val deadline = System.currentTimeMillis() + 10_000
        while (!BugCapsule.isRecording && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("capture started after the background preparation", BugCapsule.isRecording)
    }
}
