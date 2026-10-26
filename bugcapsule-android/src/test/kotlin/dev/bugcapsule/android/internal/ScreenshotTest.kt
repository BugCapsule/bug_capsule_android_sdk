package dev.bugcapsule.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.*
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleCodec
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ScreenshotTest {
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAC0lEQVR4nGNgAAMAAAcAAbKGrPQAAAAASUVORK5CYII=")
    @After fun reset() { BugCapsule.resetForTest(); BugCapsule.testOverrides = null }
    private fun start(i: ScreenshotIntegration) {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(Files.createTempDirectory("screenshot-test").toFile())
        val app: Application = ApplicationProvider.getApplicationContext()
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).build(), listOf(i))
    }
    @Test fun portableFrameAndThrottle() {
        val i = ScreenshotIntegration(enabled = true, captureRedactedPng = { png })
        start(i); assertTrue(i.capture()); assertFalse(i.capture())
        val result = BugCapsule.capture().get(10, TimeUnit.SECONDS)!!
        val capsule = CapsuleCodec.decode(result.file.readBytes())
        assertEquals(java.util.Base64.getEncoder().encodeToString(png), capsule.events.single { it.type == "screenshot" }.data["base64"])
    }
    @Test fun optInBoundsAndPrivacySkip() {
        val off = ScreenshotIntegration(captureRedactedPng = { fail("must not run"); null })
        start(off); assertFalse(off.capture())
        val small = ScreenshotIntegration(enabled = true, maxFrameBytes = 1, captureRedactedPng = { png })
        start(small); assertFalse(small.capture())
        val skip = ScreenshotIntegration(enabled = true, captureRedactedPng = { null })
        start(skip); assertFalse(skip.capture())
    }
}
