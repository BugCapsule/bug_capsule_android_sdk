package dev.bugcapsule.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.*
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.core.PATCH_RUNTIME_DEPENDENCY
import dev.bugcapsule.android.core.PatchBinding
import dev.bugcapsule.android.core.PatchIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A real capture through `BugCapsule`, not a hand-built manifest. */
@RunWith(RobolectricTestRunner::class)
class PatchCaptureTest {
    private val hash = "sha256:" + "a".repeat(64)
    private val digest = "sha256:" + "b".repeat(64)

    private class Fake(private val id: () -> PatchIdentity?) : PatchBinding {
        override val identity: PatchIdentity? get() = id()
        override fun pinForReplay(bundle: ByteArray?, keyring: ByteArray?, keyringSignature: ByteArray?) = id()
    }

    @After fun reset() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    private fun capture(binding: PatchBinding?): Capsule {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(Files.createTempDirectory("patch-capture").toFile())
        val app: Application = ApplicationProvider.getApplicationContext()
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).patchBinding(binding).build(), emptyList())
        return CapsuleCodec.decode(BugCapsule.capture().get(10, TimeUnit.SECONDS)!!.file.readBytes())
    }

    @Test fun aCaptureOnAPatchRecordsItAndRequiresTheRuntime() {
        val c = capture(Fake { PatchIdentity("build-1", digest, 1, hash, hash) })
        assertEquals(digest, c.manifest.patch?.patchSetDigest)
        assertEquals(hash, c.manifest.patch?.artifactDigest)
        assertEquals(listOf(PATCH_RUNTIME_DEPENDENCY), c.manifest.dependencies.filter { it.required }.map { it.kind })
        assertEquals(2, c.specVersion)
    }

    @Test fun aCaptureOnTheBareBaseNeedsNoRuntime() {
        val c = capture(Fake { PatchIdentity("build-1", null, 1, hash) })
        assertEquals("build-1", c.manifest.patch?.baseBuildId)
        assertTrue(c.manifest.dependencies.none { it.required })
    }

    @Test fun withoutABindingACaptureIsUnchanged() {
        assertNull(capture(null).manifest.patch)
    }

    @Test fun aFailingBindingNeverBreaksACapture() {
        assertNull(capture(Fake { error("runtime gone") }).manifest.patch)
    }
}
