package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BC-ENT-036: the flight recorder keeps its events within a retained-byte budget. */
class RetainedBudgetTest {
    private val launch = Checkpoint(CheckpointKind.LAUNCH, 0, 1000, 0)

    private fun http(body: Int) = linkedMapOf<String, Any?>(
        "method" to "GET",
        "url" to "https://shop.test/items",
        "status" to 200,
        "key" to "k",
        "bodyEncoding" to "utf8",
        "responseBody" to "x".repeat(body),
    )

    @Test
    fun theDefaultBudgetIsEightMebibytes() {
        assertEquals(8L * 1024 * 1024, FlightRecorder(launch).maxRetainedBytes)
        assertEquals(8L * 1024 * 1024, dev.bugcapsule.android.BugCapsuleConfig.builder().build().maxRetainedBytes)
    }

    @Test
    fun bodiesOfTheOldestEventsGoFirst() {
        val r = FlightRecorder(launch, maxRetainedBytes = 25_000)
        repeat(3) { r.add(it.toLong(), EventTypes.HTTP, http(10_000)) }
        assertTrue(r.retainedBytes <= 25_000)
        val w = r.window()
        assertEquals("no event is evicted while bodies can go", 3, w.events.size)
        assertNull(w.events[0].data["responseBody"])
        assertEquals("omitted", w.events[0].data["bodyEncoding"])
        assertEquals("the request key stays: replay still matches", "k", w.events[0].data["key"])
        assertEquals(10_000, (w.events[2].data["responseBody"] as String).length)
        assertEquals(1, w.truncatedBodies)
        assertEquals(0, w.budgetEvicted)
    }

    @Test
    fun thenTheOldestEventsGo() {
        val r = FlightRecorder(launch, maxRetainedBytes = 10_000)
        repeat(50) { r.add(it.toLong(), EventTypes.LOG, mapOf("message" to "m".repeat(500))) }
        assertTrue(r.retainedBytes <= 10_000)
        val w = r.window()
        assertTrue(w.events.size < 50)
        assertEquals(50L, w.events.last().seq)
        assertEquals(50 - w.events.size, w.budgetEvicted)
        assertTrue("evicted events leave a gap without a later checkpoint", w.hasGap)
    }

    @Test
    fun aBodyLargerThanTheBudgetIsDroppedFromTheNewestEvent() {
        val r = FlightRecorder(launch, maxRetainedBytes = 5_000)
        r.add(0, EventTypes.LOG, mapOf("message" to "hello"))
        r.add(1, EventTypes.HTTP, http(50_000))
        val w = r.window()
        assertEquals(2, w.events.size)
        assertNull(w.events[1].data["responseBody"])
        assertEquals(1, w.truncatedBodies)
    }

    @Test
    fun interactionPayloadsAreBodiesToo() {
        val r = FlightRecorder(launch, maxRetainedBytes = 6_000)
        val payload = linkedMapOf<String, Any?>("mode" to "full", "kind" to "json", "value" to "p".repeat(5_000))
        r.add(0, "ws.message", linkedMapOf("dep" to "ws:1", "payload" to payload))
        r.add(1, "ws.message", linkedMapOf("dep" to "ws:1", "payload" to payload))
        val w = r.window()
        assertEquals(2, w.events.size)
        assertEquals(mapOf("mode" to "omitted", "replayable" to false, "kind" to "json"), w.events[0].data["payload"])
        assertNotNull(J.obj(w.events[1].data["payload"])!!["value"])
    }

    @Test
    fun aRewriteRecountsTheRetainedBytes() {
        val r = FlightRecorder(launch)
        repeat(3) { r.add(it.toLong(), EventTypes.HTTP, http(1_000)) }
        val before = r.retainedBytes
        r.rewrite(event = { e -> e.copy(data = RetainedBytes.withoutHttpBodies(e.data)) })
        assertTrue(r.retainedBytes < before - 2_500)
    }

    @Test
    fun retainedBytesStayWithinBudgetUnderLoad() {
        // ~1k requests a second for 30 s with 4-64 KiB bodies.
        val budget = 1L * 1024 * 1024
        val r = FlightRecorder(launch, maxEvents = 20_000, maxRetainedBytes = budget)
        val random = java.util.Random(7)
        for (i in 0 until 30_000) {
            r.add(i.toLong(), EventTypes.HTTP, http(4_096 + random.nextInt(60_000)))
            assertTrue(r.retainedBytes <= budget)
        }
        assertTrue(r.window().truncatedBodies > 0)
    }
}
