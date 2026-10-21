package dev.bugcapsule.okhttp

import android.content.Context
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.WorkToken
import dev.bugcapsule.android.core.Capsule
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.FixtureStore
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.core.ReplaySettings
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A session without Android: records events in memory; optional replay context. */
class FakeSession(
    override var captureNetworkBodies: Boolean = true,
    override val maxBodyBytes: Int = 256 * 1024,
    override val redactor: Redactor = Redactor(),
) : BugCapsuleSession {
    val events: MutableList<Pair<String, Map<String, Any?>>> = Collections.synchronizedList(ArrayList())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    override val config: BugCapsuleConfig = BugCapsuleConfig.builder().build()
    override val mode = CaptureMode.QA
    override var isRecording = true
    override val isReplaying get() = replay != null
    override val applicationContext: Context get() = throw UnsupportedOperationException()
    override var replay: ReplayContext? = null
    override fun now() = System.currentTimeMillis()
    override fun record(type: String, data: Map<String, Any?>) {
        events.add(type to data)
    }
    override fun reportError(error: Throwable) {}
    override fun schedule(delayMs: Long, task: Runnable) {
        scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

    fun http(): List<Map<String, Any?>> = events.filter { it.first == "http" }.map { it.second }

    fun awaitHttp(n: Int, timeoutMs: Long = 5000): List<Map<String, Any?>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (http().size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return http()
    }
}

class FakeReplay(capsule: Capsule, override val settings: ReplaySettings = ReplaySettings()) : ReplayContext {
    override val capsule = capsule
    override val fixtures = FixtureStore(capsule.events)
    val divergences: MutableList<Divergence> = Collections.synchronizedList(ArrayList())
    val served = AtomicInteger()
    val work = AtomicInteger()
    override fun diverge(type: String, detail: String) {
        divergences.add(Divergence(type, detail))
    }
    override fun fixtureServed() {
        served.incrementAndGet()
    }
    override fun beginWork(): WorkToken {
        work.incrementAndGet()
        return WorkToken { work.decrementAndGet() }
    }
    override fun activity() {}
    override val inputsStarted = true

    companion object {
        fun of(events: List<CapsuleEvent>) = FakeReplay(dev.bugcapsule.android.core.CapsuleCodec.fromJson(capsuleJson(events)))

        private fun capsuleJson(events: List<CapsuleEvent>): Map<String, Any?> = mapOf(
            "format" to "bugcapsule.capsule",
            "specVersion" to 1L,
            "manifest" to mapOf(
                "capsuleId" to "c1", "createdAt" to "2026-10-19T10:00:00.000Z", "mode" to "qa",
                "app" to mapOf("version" to "1", "build" to "1"), "platform" to mapOf("os" to "android"),
                "session" to mapOf("id" to "s", "startedAt" to "2026-10-19T10:00:00.000Z", "wallClockAtAnchorMs" to 1L, "seed" to 1L, "durationMs" to 1L),
                "eligibility" to mapOf("status" to "eligible"),
            ),
            "checkpoint" to mapOf("kind" to "launch", "t" to 0L, "wallClockMs" to 0L),
            "events" to events.map { it.toJson() },
        )
    }
}
