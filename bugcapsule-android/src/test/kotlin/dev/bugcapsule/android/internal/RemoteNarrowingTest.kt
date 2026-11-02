package dev.bugcapsule.android.internal

import dev.bugcapsule.android.BugCapsuleBackendConfig
import dev.bugcapsule.android.ConnectionToken
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.monitoring.PropagationTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

/**
 * Server settings can only narrow what the app configured (BC-ENT-018), and
 * the ingest key never travels over plain http to a remote host.
 */
class RemoteNarrowingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------ maxCapsuleBytes

    @Test
    fun remoteMaxCapsuleBytesIsClampedToTheBackendMaximum() {
        fun max(v: Any) = RemoteCaptureConfig.fromJson(mapOf("maxCapsuleBytes" to v)).maxCapsuleBytes
        assertEquals(50L * 1024 * 1024, MAX_REMOTE_CAPSULE_BYTES)
        assertEquals(MAX_REMOTE_CAPSULE_BYTES, max(Long.MAX_VALUE))
        assertEquals(MAX_REMOTE_CAPSULE_BYTES, max(1L shl 62))
        assertEquals(MAX_REMOTE_CAPSULE_BYTES, max(MAX_REMOTE_CAPSULE_BYTES + 1))
        assertEquals(1L, max(-5L))
        assertEquals(1L, max(Long.MIN_VALUE))
        assertEquals(2048L, max(2048L))
        // The cache goes through the same parser.
        val huge = RemoteCaptureConfig.fromJson(mapOf("maxCapsuleBytes" to Long.MAX_VALUE), fetchedAtMs = 1)
        assertEquals(MAX_REMOTE_CAPSULE_BYTES, RemoteCaptureConfig.fromJson(huge.toJson()).maxCapsuleBytes)
    }

    @Test
    fun crashJournalLimitNeverOverflows() {
        assertEquals(2L * MAX_REMOTE_CAPSULE_BYTES, journalLimit(MAX_REMOTE_CAPSULE_BYTES))
        assertEquals(Long.MAX_VALUE, journalLimit(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, journalLimit(Long.MAX_VALUE / 2 + 1))
        assertTrue(journalLimit(Long.MAX_VALUE / 2) > 0)
        assertEquals(0L, journalLimit(-1))
    }

    // ------------------------------------------------ trace propagation

    @Test
    fun aServerListCanRemoveDestinationsButNotAddThem() {
        val app = listOf("api.shop.test")
        val server = listOf("api.shop.test/cart", "evil.test")
        assertTrue(PropagationTargets.matches("https://api.shop.test/cart/1", app, server))
        assertFalse("narrowed away", PropagationTargets.matches("https://api.shop.test/profile", app, server))
        assertFalse("the app never listed it", PropagationTargets.matches("https://evil.test/x", app, server))
        assertFalse("an empty server list narrows to nothing", PropagationTargets.matches("https://api.shop.test/cart", app, emptyList()))
    }

    @Test
    fun serverEntriesArePlainTextNeverPatterns() {
        val app = listOf("api.shop.test")
        assertFalse(PropagationTargets.matches("https://api.shop.test/cart", app, listOf("/.*/")))
        // A foreign host the server names stays out: the app never listed it.
        assertFalse(PropagationTargets.matches("https://evil.test/", app, listOf("/.*/", "evil.test")))
    }

    @Test
    fun appRegexesStillWorkAndWithoutAServerListTheAppListApplies() {
        val app = listOf("/^https://api\\./")
        assertTrue(PropagationTargets.matches("https://api.shop.test/cart", app))
        assertTrue(PropagationTargets.matches("https://api.shop.test/cart", app, null))
        assertFalse(PropagationTargets.matches("https://cdn.shop.test/a.png", app))
        assertTrue(PropagationTargets.matches("https://api.shop.test/cart", app, listOf("shop.test/cart")))
        assertFalse(PropagationTargets.matches("https://api.shop.test/me", app, listOf("shop.test/cart")))
    }

    // -------------------------------------------------------- Retry-After

    @Test
    fun retryAfterIsCappedAtADay() {
        var now = 1_000_000L
        val o = UploadOutbox(tmp.root.resolve("outbox"), nowMs = { now }, random = Random(1))
        o.enqueue("a", CapsuleCodec.gzip(ByteArray(100)))
        val week = 7L * 24 * 60 * 60 * 1000
        o.flush({ UploadResult(UploadStatus.RETRY_LATER, retryAfterMs = week) }, { true })
        val delay = o.retryAtMs!! - now
        assertEquals(24L * 60 * 60 * 1000, MAX_RETRY_AFTER_MS)
        assertTrue("capped at 24 h ± 20 %: $delay", delay <= MAX_RETRY_AFTER_MS * 1.2)
        assertTrue("still honoured: $delay", delay >= MAX_RETRY_AFTER_MS * 0.8)
    }

    // ------------------------------------------------ explicit backend

    private fun refusal(url: String, key: String = "bct_abcdefghijklmnopqrstuvwx") =
        ConnectionToken.explicitBackendRefusal(BugCapsuleBackendConfig(url, key))

    @Test
    fun explicitBackendAcceptsHttpsAndLocalDevelopmentHosts() {
        assertNull(refusal("https://bugs.example.com"))
        assertNull(refusal("https://192.168.1.20:8787"))
        assertNull(refusal("http://127.0.0.1:8787"))
        assertNull(refusal("http://localhost:8787"))
        assertNull(refusal("http://10.0.2.2:8787"))
        assertNull(refusal("http://[::1]:8787"))
    }

    @Test
    fun explicitBackendRefusesPlainHttpToARemoteOrLanHost() {
        assertTrue(refusal("http://api.example.com")!!.contains("https"))
        assertTrue(refusal("http://192.168.1.20:8787")!!.contains("https"))
        assertTrue(refusal("http://10.0.2.3")!!.contains("https"))
        assertTrue(refusal("ftp://api.example.com")!!.contains("https"))
        assertTrue(refusal("not a url")!!.contains("https"))
    }

    @Test
    fun explicitBackendRefusesASecretKey() {
        assertTrue(refusal("https://bugs.example.com", "bcs_abcdefghijklmnopqrstuvwx")!!.contains("secret"))
    }
}
