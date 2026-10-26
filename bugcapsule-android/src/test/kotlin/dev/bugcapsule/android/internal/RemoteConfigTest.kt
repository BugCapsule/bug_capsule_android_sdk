package dev.bugcapsule.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

class RemoteConfigTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private val results = ArrayDeque<ConfigFetchResult>()
    private val changes = ArrayList<Triple<RemoteCaptureConfig?, RemoteCaptureConfig, Boolean>>()
    private var fetches = 0

    private fun config(enabled: Boolean = true, ttl: Long = 300, rate: Double = 1.0, keys: List<String> = emptyList()) =
        RemoteCaptureConfig(enabled, rate, 5_000_000, true, keys, ttl, now)

    private fun controller(cache: RemoteConfigCache = FileConfigCache(tmp.root.resolve("rc.json")), scope: String = "s1") =
        RemoteConfigController(
            fetch = { fetches++; results.removeFirst() },
            cache = cache,
            scope = scope,
            onChange = { p, n, r -> changes.add(Triple(p, n, r)) },
            scheduler = { _, _ -> Cancellable {} },
            nowMs = { now },
            random = Random(3),
        )

    @Test
    fun ttlDrivesRefreshAndFreshness() {
        val c = controller()
        results.add(ConfigFetchResult.Fetched(config(ttl = 120)))
        c.refresh()
        assertEquals(120_000L, c.scheduledDelayMs)
        assertTrue(c.isFresh)
        c.ensureFresh()
        assertEquals(1, fetches)
        now += 121_000
        assertFalse(c.isFresh)
        results.add(ConfigFetchResult.Fetched(config()))
        c.ensureFresh()
        assertEquals(2, fetches)
    }

    @Test
    fun ttlHasAMinimumInterval() {
        val c = controller()
        results.add(ConfigFetchResult.Fetched(config(ttl = 1)))
        c.refresh()
        assertEquals(30_000L, c.scheduledDelayMs)
    }

    @Test
    fun failuresBackOffWithJitterAndRetryAfter() {
        val c = controller()
        results.add(ConfigFetchResult.Failed("offline"))
        c.refresh()
        assertTrue(c.scheduledDelayMs!! in 24_000..36_000)
        results.add(ConfigFetchResult.Failed("offline"))
        c.refresh()
        assertTrue(c.scheduledDelayMs!! in 48_000..72_000)
        repeat(10) { results.add(ConfigFetchResult.Failed("offline")); c.refresh() }
        assertTrue("capped at 30 min ± 20 %", c.scheduledDelayMs!! <= 36 * 60_000)
        results.add(ConfigFetchResult.Failed("busy", 503, retryAfterMs = 3_000_000))
        c.refresh()
        assertTrue(c.scheduledDelayMs!! >= 2_400_000)
    }

    @Test
    fun cacheIsScopedPerBackendAndKey() {
        val cache = FileConfigCache(tmp.root.resolve("rc.json"))
        val a = controller(cache, "scopeA")
        results.add(ConfigFetchResult.Fetched(config(enabled = false)))
        a.refresh()
        val sameScope = controller(cache, "scopeA").apply { loadCached() }
        assertEquals(false, sameScope.current?.enabled)
        val other = controller(cache, "scopeB").apply { loadCached() }
        assertNull(other.current)
    }

    @Test
    fun cachedKillSwitchAppliesHoweverOld() {
        val cache = FileConfigCache(tmp.root.resolve("rc.json"))
        controller(cache).apply { results.add(ConfigFetchResult.Fetched(config(enabled = false))); refresh() }
        now += 30L * 24 * 3600 * 1000
        val c = controller(cache).apply { loadCached() }
        assertFalse(c.captureAllowed)
        assertFalse(c.uploadsAllowed)
    }

    @Test
    fun uploadsNeedAFreshEnoughSuccessfulFetch() {
        val c = controller()
        assertFalse("no config yet", c.uploadsAllowed)
        assertTrue("capture follows local settings", c.captureAllowed)
        results.add(ConfigFetchResult.Fetched(config()))
        c.refresh()
        assertTrue(c.uploadsAllowed)
        now += 8L * 24 * 3600 * 1000
        assertFalse("older than maxStale (7 days)", c.uploadsAllowed)
    }

    @Test
    fun killSwitchIsNotified() {
        val c = controller()
        results.add(ConfigFetchResult.Fetched(config()))
        results.add(ConfigFetchResult.Fetched(config(enabled = false)))
        c.refresh(); c.refresh()
        assertFalse(changes.last().second.enabled)
        assertFalse(changes.last().third)
        assertFalse(c.captureAllowed)
    }

    @Test
    fun unauthorizedStopsCaptureForTheSession() {
        val c = controller()
        results.add(ConfigFetchResult.Fetched(config()))
        c.refresh()
        results.add(ConfigFetchResult.Failed("HTTP 401", 401))
        c.refresh()
        assertTrue(c.keyRejected)
        assertTrue(changes.last().third)
        assertFalse(c.captureAllowed)
        assertFalse(c.uploadsAllowed)
        // A later success lifts it.
        results.add(ConfigFetchResult.Fetched(config()))
        c.refresh()
        assertTrue(c.uploadsAllowed)
    }

    @Test
    fun pauseStopsTimerAndResumeRefreshesWhenStale() {
        val c = controller()
        results.add(ConfigFetchResult.Fetched(config(ttl = 60)))
        c.refresh()
        c.onPaused()
        assertNull(c.scheduledDelayMs)
        now += 10_000
        c.onResumed()
        assertEquals(1, fetches)
        assertEquals(50_000L, c.scheduledDelayMs)
        c.onPaused()
        now += 100_000
        results.add(ConfigFetchResult.Fetched(config()))
        c.onResumed()
        assertEquals(2, fetches)
    }

    @Test
    fun parsesAndClampsBackendJson() {
        val c = RemoteCaptureConfig.fromJson(
            mapOf("enabled" to true, "sampleRate" to 3L, "configTtlSeconds" to 999_999L, "redactKeys" to listOf("a"), "maxCapsuleBytes" to 0L),
            fetchedAtMs = 5,
        )
        assertEquals(1.0, c.sampleRate, 0.0)
        assertEquals(86_400L, c.configTtlSeconds)
        assertEquals(1L, c.maxCapsuleBytes)
        assertEquals(listOf("a"), c.redactKeys)
        try {
            RemoteCaptureConfig.fromJson(mapOf("enabled" to "yes"))
            throw AssertionError("accepted a string")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun monitoringFieldsParseAndSurviveTheCache() {
        val c = RemoteCaptureConfig.fromJson(
            mapOf(
                "monitoringEnabled" to false, "errorSampleRate" to 0.5, "tracesSampleRate" to 2.0,
                "sessionTracking" to false, "maxBreadcrumbs" to 500L, "tracePropagationTargets" to listOf("api.shop.com"),
                "storeIpAddresses" to true, "apdexThresholdMs" to 300L,
            ),
            fetchedAtMs = 1,
        )
        assertEquals(false, c.monitoringEnabled)
        assertEquals(0.5, c.errorSampleRate!!, 0.0)
        assertEquals("clamped", 1.0, c.tracesSampleRate!!, 0.0)
        assertEquals(false, c.sessionTracking)
        assertEquals(100, c.maxBreadcrumbs)
        assertEquals(listOf("api.shop.com"), c.tracePropagationTargets)
        assertEquals(c, RemoteCaptureConfig.fromJson(c.toJson()))
        val old = RemoteCaptureConfig.fromJson(mapOf("enabled" to true), fetchedAtMs = 1)
        assertEquals(true, old.monitoringEnabled)
        assertEquals(null, old.errorSampleRate)
        assertEquals(null, old.tracePropagationTargets)
    }

    @Test
    fun disabledAdaptersParseAndSurviveTheCache() {
        val c = RemoteCaptureConfig.fromJson(mapOf("disabledAdapters" to listOf("bugcapsule-okhttp.websocket")), fetchedAtMs = 1)
        assertEquals(listOf("bugcapsule-okhttp.websocket"), c.disabledAdapters)
        assertEquals(c, RemoteCaptureConfig.fromJson(c.toJson()))
        assertEquals(emptyList<String>(), RemoteCaptureConfig.fromJson(mapOf("enabled" to true), fetchedAtMs = 1).disabledAdapters)
    }
}
