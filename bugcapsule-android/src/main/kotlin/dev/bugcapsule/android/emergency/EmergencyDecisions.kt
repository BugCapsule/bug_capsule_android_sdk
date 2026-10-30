package dev.bugcapsule.android.emergency

import dev.bugcapsule.android.core.CapsuleEvent
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.internal.SdkLog
import java.security.MessageDigest

/** `manifest.capabilities` key that says whether a capsule holds every decision (emergency-client-v1 §7.8). */
internal const val EMERGENCY_DECISIONS_CAPABILITY = "emergency.decisions"

/** The subject as written in a capsule: never the subject itself (§7.8). */
internal fun emergencySubjectTag(subject: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("bugcapsule.emergency.subject:$subject".toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * The latest decision of each feature key and subject, and whether all of
 * them could be kept. A decision is written when it changes, because apps may
 * evaluate on every layout.
 *
 * Thread-safe. [track] on an unchanged decision only takes a short lock. One
 * thread at a time writes queued events, so they land in decision order and
 * no evaluation waits for another thread's `beforeRecord` hook.
 */
internal class EmergencyDecisionTracker(
    private val maxPairs: Int = MAX_PAIRS,
    /** Called once, off the lock, when [complete] becomes false. */
    private val onIncomplete: () -> Unit = {},
) {
    private class Tracked(val decision: EmergencyDecision, val data: Map<String, Any?>)

    private val lock = Any()
    private val latest = HashMap<Pair<String, String?>, Tracked>()
    private val tags = HashMap<String, String>()
    private val queue = ArrayDeque<Map<String, Any?>>()
    private var writing = false

    /** False once a decision could not be recorded. */
    @Volatile
    var complete = true
        private set

    /** Passes the decision's event data to [write] when it changed. Never throws what [write] throws. */
    fun track(decision: EmergencyDecision, subject: String?, write: (Map<String, Any?>) -> Unit) {
        var overLimit = false
        synchronized(lock) {
            val tag = subject?.let(::tagOf)
            val pair = decision.featureKey to tag
            val previous = latest[pair]
            if (previous != null && same(previous.decision, decision)) return
            if (previous == null && latest.size >= maxPairs) {
                overLimit = true
            } else {
                val data = eventData(decision, tag)
                latest[pair] = Tracked(decision, data)
                queue.addLast(data)
            }
        }
        if (overLimit) markIncomplete() else drain(write)
    }

    /** Passes every tracked decision to [write]: those made before recording started, and after a periodic checkpoint. */
    fun writeAll(write: (Map<String, Any?>) -> Unit) {
        synchronized(lock) {
            if (latest.isEmpty()) return
            for (t in latest.values) queue.addLast(t.data)
        }
        drain(write)
    }

    fun markIncomplete() {
        synchronized(lock) {
            if (!complete) return
            complete = false
        }
        onIncomplete()
    }

    /** [emergencySubjectTag], cached: hashing on every evaluation adds up. */
    fun tagOf(subject: String): String = synchronized(lock) {
        tags[subject] ?: run {
            if (tags.size >= MAX_PAIRS) tags.clear()
            emergencySubjectTag(subject).also { tags[subject] = it }
        }
    }

    fun clear() = synchronized(lock) {
        latest.clear()
        tags.clear()
        queue.clear()
        complete = true
    }

    private fun drain(write: (Map<String, Any?>) -> Unit) {
        synchronized(lock) {
            if (writing) return
            writing = true
        }
        while (true) {
            val data = synchronized(lock) {
                queue.removeFirstOrNull().also { if (it == null) writing = false }
            } ?: return
            try {
                write(data)
            } catch (e: Throwable) {
                SdkLog.once("could not record an emergency decision: $e")
                markIncomplete()
            }
        }
    }

    companion object {
        const val MAX_PAIRS = 256

        /** Every field an event holds: two entries in the same state are the same decision (§7.8). */
        private fun same(a: EmergencyDecision, b: EmergencyDecision) =
            a.action == b.action && a.source == b.source && a.reasonCode == b.reasonCode &&
                a.policyId == b.policyId && a.policyRevision == b.policyRevision &&
                a.documentRevision == b.documentRevision && a.publicUserMessage == b.publicUserMessage &&
                a.requiredVersion == b.requiredVersion && a.fallback == b.fallback &&
                a.fallbackAvailable == b.fallbackAvailable

        private fun eventData(d: EmergencyDecision, tag: String?): Map<String, Any?> {
            val data = linkedMapOf<String, Any?>("key" to d.featureKey)
            if (tag != null) data["subject"] = tag
            data["action"] = d.action.name
            data["source"] = d.source.wire
            d.reasonCode?.let { data["reasonCode"] = it }
            d.policyId?.let { data["policyId"] = it }
            d.policyRevision?.let { data["policyRevision"] = it }
            d.documentRevision?.let { data["documentRevision"] = it }
            d.publicUserMessage?.let { data["message"] = it }
            d.requiredVersion?.let { data["requiredVersion"] = it }
            d.fallback?.let { data["fallback"] = it }
            if (d.fallbackAvailable) data["fallbackAvailable"] = true
            return data
        }
    }
}

/** The decisions of a capsule, looked up during replay (§7.8). Immutable: any thread may read it. */
internal class EmergencyDecisionScript(events: Iterable<CapsuleEvent>) {
    private class Entry(val subject: String?, val t: Long, val decision: EmergencyDecision)

    private val byKey = HashMap<String, MutableList<Entry>>()

    init {
        for (e in events) {
            if (e.type != EventTypes.EMERGENCY_DECISION) continue
            val key = J.str(e.data["key"]) ?: continue
            val entry = parse(key, e) ?: continue
            byKey.getOrPut(key) { ArrayList() }.add(entry)
        }
    }

    /** The decision the app saw for [key] at recorded time [t]; null when the capsule has none for it. */
    fun lookup(key: String, subjectTag: String?, t: Long): EmergencyDecision? {
        val all = byKey[key] ?: return null
        val entries = all.filter { it.subject == subjectTag }.ifEmpty { all }
        var latest: Entry? = null
        var earliest: Entry? = null
        for (e in entries) {
            if (earliest == null || e.t < earliest.t) earliest = e
            if (e.t <= t && (latest == null || e.t >= latest.t)) latest = e
        }
        return (latest ?: earliest)?.decision
    }

    private fun parse(key: String, e: CapsuleEvent): Entry? {
        val d = e.data
        // By name, not the document parser: that blocks an unknown action, which only a document may do.
        val action = EmergencyAction.entries.firstOrNull { it.name == d["action"] } ?: return null
        return Entry(
            J.str(d["subject"]),
            e.t,
            EmergencyDecision(
                featureKey = key,
                allowed = action == EmergencyAction.ALLOW,
                action = action,
                reasonCode = J.str(d["reasonCode"]),
                policyId = J.str(d["policyId"]),
                policyRevision = J.long(d["policyRevision"]),
                documentRevision = J.long(d["documentRevision"]),
                publicUserMessage = J.str(d["message"]),
                requiredVersion = J.str(d["requiredVersion"]),
                fallback = J.str(d["fallback"]),
                fallbackAvailable = d["fallbackAvailable"] == true,
                source = EmergencyDecisionSource.REPLAY,
            ),
        )
    }
}
