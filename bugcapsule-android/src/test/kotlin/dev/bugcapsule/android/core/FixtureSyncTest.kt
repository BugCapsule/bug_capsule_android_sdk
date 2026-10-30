package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The vendored copy (`spec-fixtures/`) must equal the source of truth in the
 * sibling `bug_capsule_flutter_sdk/spec/fixtures` when it is checked out.
 * Re-sync with `bug_capsule_flutter_sdk/tool/sync_spec.sh` (copy `capsule-v2`
 * by hand until the script copies it to the Android SDK).
 */
class FixtureSyncTest {
    private fun tree(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".json") }
        .associate { it.relativeTo(root).path to it.readText() }

    @Test
    fun vendoredFixturesExist() {
        assertTrue(File(SpecFixtures.dir, "capsule-v1/valid").isDirectory)
        assertTrue(File(SpecFixtures.dir, "capsule-v2/valid").isDirectory)
        assertTrue(File(SpecFixtures.dir, "conformance/interactions.json").isFile)
        assertTrue(File(SpecFixtures.dir, "conformance/request-keys.json").isFile)
        assertTrue(File(SpecFixtures.dir, "connection-token-v1/vectors.json").isFile)
        assertTrue(File(SpecFixtures.dir, "emergency-v1/envelopes.json").isFile)
    }

    /** emergency-v1 is not compared: its source of truth is the backend's generator, not the Flutter SDK. */
    @Test
    fun vendoredFixturesMatchUpstream() {
        assumeTrue("upstream fixtures not checked out", SpecFixtures.upstream.isDirectory)
        for (sub in listOf("capsule-v1", "capsule-v2", "conformance", "connection-token-v1")) {
            val upstream = tree(File(SpecFixtures.upstream, sub))
            val vendored = tree(File(SpecFixtures.dir, sub))
            assertEquals("file list of $sub differs; run tool/sync_spec.sh", upstream.keys.sorted(), vendored.keys.sorted())
            for ((name, text) in upstream) {
                assertEquals("$sub/$name differs from upstream; run tool/sync_spec.sh", text, vendored[name])
            }
        }
    }
}
