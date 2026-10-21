package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.feedback.ShakeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadsTest {
    private val redactor = Redactor(listOf("loyaltyId"))

    @Test
    fun itemsAreRedactedButIdsAndTimestampsKept() {
        // An all-digit UUID group that the card rule could otherwise eat.
        val eventId = "45320151-1283-0366-4532-015112830366"
        val payload = linkedMapOf<String, Any?>(
            "eventId" to eventId,
            "timestamp" to "2026-10-21T14:03:10.982Z",
            "message" to linkedMapOf("formatted" to "login failed for Bearer abcdef123456 with password=hunter2"),
            "extra" to linkedMapOf("loyaltyId" to "L-99", "authToken" to "t", "note" to "card 4532015112830366"),
            "request" to linkedMapOf("url" to "https://api.shop.com/cb?code=oauth-secret&page=2", "headers" to mapOf("Authorization" to "Bearer x")),
            "contexts" to linkedMapOf("trace" to linkedMapOf("traceId" to "4bf92f3577b34da6a3ce929d0e0e4736", "spanId" to "00f067aa0ba902b7")),
            "debugImages" to listOf(linkedMapOf("type" to "proguard", "debugId" to "45320151-1283-0366-4532-015112830366")),
        )
        val out = ItemRedaction.redact(redactor, payload)
        assertEquals(eventId, out["eventId"])
        assertEquals("2026-10-21T14:03:10.982Z", out["timestamp"])
        val text = J.obj(out["message"])!!["formatted"] as String
        assertTrue(text, !text.contains("abcdef123456") && !text.contains("hunter2"))
        val extra = J.obj(out["extra"])!!
        assertEquals(Redactor.PLACEHOLDER, extra["loyaltyId"])
        assertEquals(Redactor.PLACEHOLDER, extra["authToken"])
        assertEquals("card [REDACTED]", extra["note"])
        val request = J.obj(out["request"])!!
        assertTrue((request["url"] as String).contains("page=2"))
        assertTrue(!(request["url"] as String).contains("oauth-secret"))
        assertEquals(Redactor.PLACEHOLDER, J.obj(request["headers"])!!["authorization"])
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", J.obj(J.obj(out["contexts"])!!["trace"])!!["traceId"])
        assertEquals(eventId, J.obj(J.list(out["debugImages"])!![0])!!["debugId"])
    }

    @Test
    fun tagsThatWouldRejectTheItemAreDropped() {
        val tags = Limits.tags(mapOf("screen" to "checkout", "bad key" to "x", "x".repeat(33) to "y", "long" to "v".repeat(300), "n" to null))
        assertEquals(setOf("screen", "long"), tags.keys)
        assertEquals(200, tags["long"]!!.length)
        assertEquals(10, Limits.fingerprint(List(12) { "f$it" })!!.size)
        assertNull(Limits.fingerprint(emptyList()))
    }

    @Test
    fun oversizedItemsShrinkOrAreDropped() {
        val big = linkedMapOf<String, Any?>(
            "eventId" to "e",
            "breadcrumbs" to List(100) { mapOf("message" to "m".repeat(20_000)) },
            "exception" to mapOf("values" to emptyList<Any?>()),
        )
        val fitted = Limits.fit(big, maxBytes = 300_000)
        assertNotNull(fitted)
        assertTrue(J.list(fitted!!["breadcrumbs"])!!.size <= 10)
        val hopeless = linkedMapOf<String, Any?>("eventId" to "e", "exception" to mapOf("values" to emptyList<Any?>()), "message" to "m".repeat(400_000))
        assertNull(Limits.fit(hopeless, maxBytes = 300_000))
    }

    @Test
    fun recorderEventsBecomeBreadcrumbs() {
        val d = BreadcrumbDeriver()
        val r1 = d.derive(EventTypes.ROUTE, mapOf("location" to "/cart"), 1)!!
        val r2 = d.derive(EventTypes.ROUTE, mapOf("location" to "/checkout"), 2)!!
        assertEquals("navigation", r2.type)
        assertEquals(mapOf("from" to "/cart", "to" to "/checkout"), r2.data)
        assertEquals(null, r1.data["from"])
        val http = d.derive(EventTypes.HTTP, mapOf("method" to "GET", "url" to "https://a/x", "status" to 503, "durationMs" to 40L, "responseBody" to "secret"), 3)!!
        assertEquals("http", http.type)
        assertEquals(Level.ERROR, http.level)
        assertEquals(mapOf("method" to "GET", "url" to "https://a/x", "status_code" to 503, "duration_ms" to 40L), http.data)
        assertEquals(mapOf("id" to "login.email"), d.derive(EventTypes.TEXT, mapOf("id" to "login.email", "value" to "me@x.com"), 4)!!.data)
        assertEquals(Level.WARNING, d.derive(EventTypes.LOG, mapOf("level" to "warn", "message" to "slow"), 5)!!.level)
        assertEquals("ui.action", d.derive(EventTypes.ACTION, mapOf("id" to "cart.checkout"), 6)!!.category)
        assertNull(d.derive(EventTypes.POINTER, mapOf("x" to 1), 7))
        assertEquals("app.lifecycle", d.derive(EventTypes.LIFECYCLE, mapOf("state" to "paused"), 8)!!.category)
    }

    @Test
    fun shakeNeedsTwoPeaksWithinTheWindow() {
        var shakes = 0
        val s = ShakeDetector(Runnable { shakes++ })
        s.onAcceleration(0f, 0f, 9.8f, 0) // gravity only
        s.onAcceleration(30f, 0f, 9.8f, 100)
        assertEquals(0, shakes)
        s.onAcceleration(30f, 10f, 9.8f, 300)
        assertEquals(1, shakes)
        s.onAcceleration(30f, 0f, 9.8f, 400)
        s.onAcceleration(30f, 0f, 9.8f, 500)
        assertEquals("cooldown", 1, shakes)
    }
}
