package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.internal.Cancellable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hub with a manual clock and scheduler. */
internal class ManualHub : TracingHub {
    var now = 1_700_000_000_000L
    val finished = ArrayList<Transaction>()
    private val tasks = ArrayList<Pair<Long, Runnable>>()

    override fun nowMs() = now
    override fun nanoTime() = now * 1_000_000
    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        val entry = (now + delayMs) to task
        tasks.add(entry)
        return Cancellable { tasks.remove(entry) }
    }
    override fun onTransactionFinished(transaction: Transaction) {
        finished.add(transaction)
    }
    override fun baggage(span: Span) = TraceHeaders.baggage(span.traceId, "app@1+1", "qa", span.transaction.sampleRate, span.sampled)

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            now = next.first
            next.second.run()
        }
        now = until
    }

    fun tx(name: String = "Checkout", sampled: Boolean = true) =
        Transaction(this, name, "ui.load", Ids.traceId(), Ids.spanId(), null, sampled, 1.0, null)
}

class TracingTest {
    @Test
    fun traceparentRoundTripsAndRejectsInvalidIds() {
        val tp = TraceHeaders.traceparent("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true)
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", tp)
        val parsed = TraceHeaders.parseTraceparent(tp)!!
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", parsed.traceId)
        assertTrue(parsed.sampled)
        assertNull(TraceHeaders.parseTraceparent("00-00000000000000000000000000000000-00f067aa0ba902b7-01"))
        assertNull(TraceHeaders.parseTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"))
        assertNull(TraceHeaders.parseTraceparent("garbage"))
        assertFalse(TraceHeaders.parseTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00")!!.sampled)
    }

    @Test
    fun generatedIdsHaveTheContractShape() {
        repeat(50) {
            assertTrue(Regex("^[0-9a-f]{32}$").matches(Ids.traceId()))
            assertTrue(Regex("^[0-9a-f]{16}$").matches(Ids.spanId()))
        }
    }

    @Test
    fun baggageKeepsTheAppsEntriesAndReplacesOurs() {
        val ours = TraceHeaders.baggage("abc", "com.shop@1.4.0+52", "production", 0.25, true)
        assertEquals(
            "bugcapsule-trace_id=abc,bugcapsule-release=com.shop@1.4.0+52,bugcapsule-environment=production," +
                "bugcapsule-sample_rate=0.25,bugcapsule-sampled=true",
            ours,
        )
        val merged = TraceHeaders.mergeBaggage("userId=7, bugcapsule-trace_id=old", ours)
        assertTrue(merged.startsWith("userId=7,bugcapsule-trace_id=abc"))
        assertFalse(merged.contains("old"))
        assertEquals("com.shop@1.4.0+52", TraceHeaders.parseBaggage(merged)["release"])
        assertEquals("1.0", TraceHeaders.parseBaggage(TraceHeaders.baggage("a", null, null, 1.0, null))["sample_rate"])
        assertEquals("a%20b%2Cc", TraceHeaders.baggage("x", "a b,c", null, null, null).substringAfter("release="))
    }

    @Test
    fun propagationTargetsMatchSubstringsAndRegexes() {
        val targets = listOf("api.shop.com", "/^https://.*\\.internal\\//")
        assertTrue(PropagationTargets.matches("https://api.shop.com/cart", targets))
        assertTrue(PropagationTargets.matches("https://auth.internal/token", targets))
        assertFalse(PropagationTargets.matches("https://cdn.example.com/a.png", targets))
        assertFalse(PropagationTargets.matches("https://api.shop.com/cart", emptyList()))
    }

    @Test
    fun httpStatusMapsLikeOpenTelemetry() {
        assertEquals(SpanStatus.OK, SpanStatus.fromHttpStatus(204))
        assertEquals(SpanStatus.NOT_FOUND, SpanStatus.fromHttpStatus(404))
        assertEquals(SpanStatus.RESOURCE_EXHAUSTED, SpanStatus.fromHttpStatus(429))
        assertEquals(SpanStatus.INVALID_ARGUMENT, SpanStatus.fromHttpStatus(422))
        assertEquals(SpanStatus.UNAVAILABLE, SpanStatus.fromHttpStatus(503))
        assertEquals(SpanStatus.INTERNAL_ERROR, SpanStatus.fromHttpStatus(500))
    }

    @Test
    fun idleTransactionFinishesAfterItsLastChildPlusTheIdleTimeout() {
        val hub = ManualHub()
        val tx = hub.tx()
        val ttid = tx.startChild("ui.load.initial_display")
        tx.finishWhenIdle(3_000, 30_000)
        hub.advance(500)
        ttid.finish()
        val http = tx.startChild("http.client", "GET /cart")
        hub.advance(2_000)
        assertFalse("a running child keeps it open", tx.isFinished)
        http.finish(SpanStatus.NOT_FOUND)
        val lastChildEnd = http.endTimestampMs!!
        hub.advance(3_000)
        assertTrue(tx.isFinished)
        assertEquals(lastChildEnd, tx.endTimestampMs)
        assertEquals(listOf(tx), hub.finished)
        val spans = tx.childrenSnapshot().map { it.toJson() }
        assertEquals(listOf("ui.load.initial_display", "http.client"), spans.map { it["op"] })
        assertEquals("not_found", spans[1]["status"])
        assertEquals(tx.spanId, spans[1]["parentSpanId"])
    }

    @Test
    fun deadlineFinishesOpenChildrenAsDeadlineExceeded() {
        val hub = ManualHub()
        val tx = hub.tx()
        val ttfd = tx.startChild("ui.load.full_display")
        tx.finishWhenIdle(3_000, 30_000)
        hub.advance(30_000)
        assertTrue(tx.isFinished)
        assertEquals(SpanStatus.DEADLINE_EXCEEDED, tx.status)
        assertEquals(SpanStatus.DEADLINE_EXCEEDED, ttfd.status)
    }

    @Test
    fun spansAfterFinishAreDetachedAndChildrenAreCapped() {
        val hub = ManualHub()
        val tx = hub.tx()
        repeat(Transaction.MAX_SPANS + 5) { tx.startChild("db").finish() }
        assertEquals(Transaction.MAX_SPANS, tx.childrenSnapshot().size)
        tx.finish()
        val late = tx.startChild("late")
        late.finish()
        assertTrue(late.detached)
        assertEquals(Transaction.MAX_SPANS, tx.childrenSnapshot().size)
        tx.finish()
        assertEquals("finish is idempotent", 1, hub.finished.size)
    }

    @Test
    fun spanHeadersCarryTheTraceAndDecision() {
        val hub = ManualHub()
        val tx = hub.tx(sampled = false)
        val child = tx.startChild("http.client")
        assertEquals("00-${tx.traceId}-${child.spanId}-00", child.toTraceparent())
        assertTrue(child.toBaggage().contains("bugcapsule-sampled=false"))
        assertNotNull(TraceHeaders.parseTraceparent(child.toTraceparent()))
    }
}
