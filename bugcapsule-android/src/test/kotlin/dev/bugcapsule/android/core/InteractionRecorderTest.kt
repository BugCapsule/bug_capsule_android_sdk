package dev.bugcapsule.android.core

import dev.bugcapsule.android.replay.InteractionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionRecorderTest {
    private val events = ArrayList<CapsuleEvent>()
    private var t = 0L
    private var disabled = listOf<String>()
    private var bodies = true
    private val recorder = InteractionRecorder(
        sink = { type, data -> events.add(CapsuleEvent(events.size + 1L, t, type, data)) },
        now = { t },
        redactor = { Redactor() },
        recording = { true },
        payloadsEnabled = { bodies },
        disabledAdapters = { disabled },
    )
    private val ws = DependencyDescriptor("ws", DependencyKinds.WEBSOCKET, "test.ws", "1.0", 1, operations = listOf("connect"))

    @Test
    fun binaryIsMetadataByDefaultAndMakesCoveragePartial() {
        val rec = recorder.register(ws)
        rec.record("ws.message", mapOf("conn" to "ws1", "index" to 0, "payload" to rec.encoder.encode(byteArrayOf(1, 2, 3), "binary").toJson()))
        val payload = events.single().data["payload"] as Map<*, *>
        assertEquals(mapOf("mode" to "metadata", "replayable" to false, "bytes" to 3L, "kind" to "binary"), payload)
        val dep = recorder.dependencies(events).single()
        assertEquals(DependencyCoverage.PARTIAL, dep.coverage)
        assertEquals(DependencyGaps.PAYLOAD_BINARY_METADATA, dep.gaps.single().code)
        assertEquals("ws", events.single().data["dep"])

        val keeping = recorder.register(ws.copy(id = "ws2", captureBinary = true))
        val kept = keeping.encoder.encode(byteArrayOf(1, 2, 3), "binary")
        assertEquals(PayloadMode.FULL, kept.mode)
        assertTrue((kept.decoded as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun payloadLimitAndRollingBudget() {
        recorder.limits = InteractionLimits(maxPayloadBytes = 20, maxWindowPayloadBytes = 30, windowMs = 1000)
        val rec = recorder.register(ws)
        assertEquals(PayloadMode.TRUNCATED, rec.encoder.encode("x".repeat(40), "text").mode)
        assertEquals(PayloadMode.FULL, rec.encoder.encode("a".repeat(15), "text").mode)
        assertEquals(PayloadMode.METADATA, rec.encoder.encode("b".repeat(15), "text").mode) // budget spent
        t = 2000 // the window rolled
        assertEquals(PayloadMode.FULL, rec.encoder.encode("c".repeat(15), "text").mode)
        assertEquals(1L, rec.stats.dropped)
        assertEquals(1L, rec.stats.truncated)
    }

    @Test
    fun activeLimitIsSharedAcrossAdapters() {
        recorder.limits = InteractionLimits(maxActive = 2)
        val a = recorder.register(ws)
        val b = recorder.register(ws.copy(id = "native", kind = DependencyKinds.NATIVE, adapter = "test.native"))
        assertTrue(a.open("ws", "ws1"))
        assertTrue(b.open("native.sub", "n1"))
        assertFalse(a.open("ws", "ws2"))
        assertEquals(DependencyGaps.CONNECTIONS_LIMIT, a.stats.gaps.single().code)
        b.closed("n1")
        b.closed("n1") // idempotent
        assertTrue(a.open("ws", "ws3"))
        assertEquals(
            listOf(mapOf("dep" to "ws", "type" to "ws", "id" to "ws1", "restorable" to false), mapOf("dep" to "ws", "type" to "ws", "id" to "ws3", "restorable" to false)),
            recorder.checkpoint()!!["open"],
        )
    }

    @Test
    fun remotelyDisabledAdaptersStopCapturing() {
        val rec = recorder.register(ws)
        assertTrue(rec.isCapturing)
        disabled = listOf("test.ws")
        assertFalse(rec.isCapturing)
        disabled = listOf("other.adapter")
        assertTrue(rec.isCapturing)
    }

    @Test
    fun payloadCaptureOffRecordsOmitted() {
        bodies = false
        val rec = recorder.register(ws)
        assertEquals(InteractionPayload(PayloadMode.OMITTED, false, kind = "text"), rec.encoder.encode("hi", "text"))
    }

    @Test
    fun dependenciesListOnlyUsedOnes() {
        recorder.register(ws)
        assertTrue(recorder.dependencies(events).isEmpty())
        val native = recorder.register(ws.copy(id = "loc", kind = DependencyKinds.NATIVE, adapter = "acme.location", required = false))
        native.record("native.call", mapOf("op" to "o1", "operation" to "x", "key" to "x||0", "occurrence" to 0))
        val dep = recorder.dependencies(events).single()
        assertEquals("loc", dep.id)
        assertEquals(DependencyCoverage.DIAGNOSTIC, dep.coverage)
        assertEquals(1L, dep.stats["events"])
    }

    // ---------------------------------------------------------- preflight

    private fun capsuleWith(vararg deps: CapsuleDependency, open: List<Map<String, Any?>>? = null): Capsule {
        val base = CapsuleCodec.fromJson(SpecFixtures.json("capsule-v1/valid/minimal.json")["document"] as Map<String, Any?>)
        val checkpoint = if (open == null) base.checkpoint else Checkpoint(CheckpointKind.PERIODIC, 100, 0, 0, mapOf("interactions" to mapOf("open" to open)))
        return base.copy(manifest = base.manifest.copy(dependencies = deps.toList()), checkpoint = checkpoint)
    }

    private val wsDep = CapsuleDependency("ws", DependencyKinds.WEBSOCKET, "bugcapsule-okhttp.websocket", "0.5.0", 1, true, DependencyCoverage.COMPLETE)

    @Test
    fun preflightNamesTheUnsupportedDependency() {
        val capsule = capsuleWith(wsDep, wsDep.copy(id = "diag", adapter = "acme.analytics", required = false, coverage = DependencyCoverage.DIAGNOSTIC))
        val message = InteractionReplay.preflight(capsule, emptyList())!!
        assertTrue(message, message.startsWith("dependency.unsupported: bugcapsule-okhttp.websocket schema 1 (dependency ws)"))
        // Diagnostic dependencies never block.
        assertNull(InteractionReplay.preflight(capsule, listOf(InteractionAdapterInfo("bugcapsule-okhttp.websocket", 1..1))))
        val schema = InteractionReplay.preflight(capsuleWith(wsDep.copy(schema = 3)), listOf(InteractionAdapterInfo("bugcapsule-okhttp.websocket", 1..2)))!!
        assertTrue(schema, schema.startsWith("dependency.schema: bugcapsule-okhttp.websocket schema 3"))
        // An incompatible attempt gets the dependency.unsupported reason code.
        val report = ReplayReport(ReplayOutcome.INCOMPATIBLE, 1, 0, ReplayStrategy.AUTO, message = message)
        assertEquals(listOf("dependency.unsupported"), Verdicts.evaluate(RunKind.VERIFY, 1, listOf(report)).reasonCodes)
    }

    @Test
    fun preflightRejectsOpenInteractionsThatCannotBeRestored() {
        val info = listOf(InteractionAdapterInfo("bugcapsule-okhttp.websocket", 1..1))
        assertNull(InteractionReplay.preflight(capsuleWith(wsDep, open = emptyList()), info))
        val open = listOf(mapOf<String, Any?>("dep" to "ws", "type" to "ws", "id" to "ws3", "restorable" to false))
        val message = InteractionReplay.preflight(capsuleWith(wsDep, open = open), info)!!
        assertTrue(message, message.startsWith("dependency.unrestorable: bugcapsule-okhttp.websocket (dependency ws)"))
        val restorable = listOf(mapOf<String, Any?>("dep" to "ws", "type" to "ws", "id" to "ws3", "restorable" to true))
        assertNull(InteractionReplay.preflight(capsuleWith(wsDep, open = restorable), listOf(InteractionAdapterInfo("bugcapsule-okhttp.websocket", 1..1, restorable = true))))
    }
}
