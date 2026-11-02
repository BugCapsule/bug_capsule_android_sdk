package dev.bugcapsule.android.internal

import dev.bugcapsule.android.BugCapsule
import org.junit.Assert.assertTrue

/** [BugCapsule.init] prepares capture on its executor (BC-ENT-036): waits until that is done. */
fun awaitPrepared() {
    val rt = BugCapsule.runtimeForTest() ?: return
    assertTrue("capture was not prepared in time", rt.awaitPrepared(10_000))
}
