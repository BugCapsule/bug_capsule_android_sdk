package dev.bugcapsule.ndk

import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BugCapsuleNdkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun installReturnsFalseWithoutNativeLibrary() {
        // libbugcapsule-ndk.so is not loadable on the JVM.
        assertFalse(BugCapsuleNdk.install(tmp.root.absolutePath))
        assertFalse(BugCapsuleNdk.isInstalled)
        // Second attempt hits the cached load failure, still no throw.
        assertFalse(BugCapsuleNdk.install(tmp.root.absolutePath))
        assertFalse(BugCapsuleNdk.isInstalled)
    }

    @Test
    fun uninstallAndCrashForTestingAreNoOpsWithoutNativeLibrary() {
        BugCapsuleNdk.uninstall()
        BugCapsuleNdk.crashForTesting(0)
        assertFalse(BugCapsuleNdk.isInstalled)
    }
}
