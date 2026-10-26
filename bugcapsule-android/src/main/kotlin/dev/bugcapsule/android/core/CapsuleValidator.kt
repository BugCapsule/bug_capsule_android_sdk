package dev.bugcapsule.android.core

/** Resource bounds of a document (capsule-v1 *Validation*, capsule-v2 §9). */
object CapsuleLimits {
    const val MAX_EVENTS = 200_000
    const val MAX_DEPTH = 100
    const val MAX_LABELS = 100
    const val MAX_INTEGRATIONS = 100
    const val MAX_MISSING = 500
    const val MAX_REDACTION_KEYS = 500
    const val MAX_CAPABILITIES = 200
    const val MAX_FRAMEWORKS = 20
    const val MAX_DEPENDENCIES = 100
    const val MAX_DEPENDENCY_ITEMS = 100
    const val MAX_TESTED = 20

    /** JSON values in the whole document, counted before parsing. */
    const val MAX_JSON_VALUES = 4_000_000
}

/**
 * Structural validation of a decoded capsule document (spec v1 and v2), rule
 * for rule the Dart reference (`capsule_validator.dart`) and the backend.
 * Returns null when valid, otherwise `<json path> <reason>`. Does not check
 * `specVersion` against the reader's version (the codec does that).
 */
object CapsuleValidator {
    private class Invalid(message: String) : RuntimeException(message) {
        override fun fillInStackTrace(): Throwable = this
    }

    private const val MAX_SAFE_INT = 9007199254740991L

    @JvmStatic
    fun validate(doc: Any?): String? = try {
        validateDoc(doc)
        null
    } catch (e: Invalid) {
        e.message
    }

    private fun fail(path: String, reason: String): Nothing = throw Invalid("$path $reason")

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, path: String): Map<String, Any?> {
        if (v !is Map<*, *>) fail(path, "must be an object")
        return v as Map<String, Any?>
    }

    private fun optObj(o: Map<String, Any?>, key: String, path: String): Map<String, Any?>? {
        val v = o[key] ?: return null
        return obj(v, "$path.$key")
    }

    private fun array(v: Any?, path: String, max: Int): List<Any?> {
        if (v !is List<*>) fail(path, "must be an array")
        if (v.size > max) fail(path, "must have at most $max items")
        return v
    }

    private fun string(o: Map<String, Any?>, key: String, path: String, max: Int = 1000, min: Int = 1): String {
        val v = o[key]
        if (v !is String) fail("$path.$key", "must be a string")
        if (v.length < min) fail("$path.$key", "must not be empty")
        if (v.length > max) fail("$path.$key", "must be at most $max characters")
        return v
    }

    private fun optString(o: Map<String, Any?>, key: String, path: String, max: Int = 1000) {
        val v = o[key] ?: return
        if (v !is String) fail("$path.$key", "must be a string or null")
        if (v.length > max) fail("$path.$key", "must be at most $max characters")
    }

    private fun isInt(v: Any?): Boolean = (v is Long && Math.abs(v) <= MAX_SAFE_INT) || v is Int

    private fun int(o: Map<String, Any?>, key: String, path: String, min: Long? = null): Long {
        val v = o[key]
        if (!isInt(v)) fail("$path.$key", "must be an integer")
        val n = (v as Number).toLong()
        if (min != null && n < min) fail("$path.$key", "must be >= $min")
        return n
    }

    private fun optInt(o: Map<String, Any?>, key: String, path: String, min: Long? = null) {
        if (o[key] == null) return
        int(o, key, path, min)
    }

    private fun optNum(o: Map<String, Any?>, key: String, path: String) {
        val v = o[key] ?: return
        if (v !is Number || !v.toDouble().isFinite()) fail("$path.$key", "must be a number or null")
    }

    private fun optBool(o: Map<String, Any?>, key: String, path: String) {
        val v = o[key] ?: return
        if (v !is Boolean) fail("$path.$key", "must be a boolean or null")
    }

    private fun enum(o: Map<String, Any?>, key: String, path: String, values: List<String>) {
        val v = o[key]
        if (v !is String || v !in values) fail("$path.$key", "must be one of ${values.joinToString(", ")}")
    }

    private fun isoDate(o: Map<String, Any?>, key: String, path: String) {
        val v = o[key]
        if (v !is String || !Iso8601.isValid(v)) fail("$path.$key", "must be an ISO-8601 date-time with a time zone")
    }

    private fun stringMap(m: Map<String, Any?>, path: String, maxEntries: Int = 1000, max: Int = 10000) {
        if (m.size > maxEntries) fail(path, "must have at most $maxEntries entries")
        for ((k, v) in m) {
            if (v !is String) fail("$path.$k", "must be a string")
            if (v.length > max) fail("$path.$k", "must be at most $max characters")
        }
    }

    private fun depth(root: Any?) {
        val stack = ArrayDeque<Pair<Any?, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (value, d) = stack.removeLast()
            if (d > CapsuleLimits.MAX_DEPTH) fail("$", "nests deeper than ${CapsuleLimits.MAX_DEPTH} levels")
            when (value) {
                is Map<*, *> -> for (v in value.values) if (v is Map<*, *> || v is List<*>) stack.addLast(v to d + 1)
                is List<*> -> for (v in value) if (v is Map<*, *> || v is List<*>) stack.addLast(v to d + 1)
            }
        }
    }

    private fun validateDoc(root: Any?) {
        val doc = obj(root, "$")
        depth(doc)
        if (doc["format"] != "bugcapsule.capsule") fail("$.format", "must be \"bugcapsule.capsule\"")
        val specVersion = int(doc, "specVersion", "$", min = 1)

        val mp = "$.manifest"
        val m = obj(doc["manifest"], mp)
        string(m, "capsuleId", mp, max = 100)
        isoDate(m, "createdAt", mp)
        enum(m, "mode", mp, listOf("debug", "qa", "production"))

        val app = obj(m["app"], "$mp.app")
        string(app, "version", "$mp.app", max = 200)
        string(app, "build", "$mp.app", max = 200)
        for (k in listOf("id", "name", "flavor")) optString(app, k, "$mp.app", max = 200)

        optObj(m, "source", mp)?.let {
            optString(it, "gitSha", "$mp.source", max = 200)
            optBool(it, "dirty", "$mp.source")
        }
        optObj(m, "sdk", mp)?.let {
            optString(it, "name", "$mp.sdk", max = 100)
            optString(it, "version", "$mp.sdk", max = 100)
        }
        optInt(m, "stateSchemaVersion", mp)

        val platform = obj(m["platform"], "$mp.platform")
        string(platform, "os", "$mp.platform", max = 100)
        optString(platform, "osVersion", "$mp.platform", max = 200)
        optString(platform, "locale", "$mp.platform", max = 100)
        optString(platform, "dartVersion", "$mp.platform", max = 200)
        optObj(platform, "screen", "$mp.platform")?.let { screen ->
            for (k in listOf("width", "height", "pixelRatio")) optNum(screen, k, "$mp.platform.screen")
        }

        val session = obj(m["session"], "$mp.session")
        string(session, "id", "$mp.session", max = 200)
        isoDate(session, "startedAt", "$mp.session")
        int(session, "wallClockAtAnchorMs", "$mp.session")
        int(session, "seed", "$mp.session")
        int(session, "durationMs", "$mp.session")

        optObj(m, "failure", mp)?.let { validateFailure(it, "$mp.failure") }
        optObj(m, "monitoring", mp)?.let {
            optString(it, "eventId", "$mp.monitoring", max = 100)
            optString(it, "traceId", "$mp.monitoring", max = 100)
            optString(it, "release", "$mp.monitoring", max = 200)
            optString(it, "environment", "$mp.monitoring", max = 64)
        }

        val eligibility = obj(m["eligibility"], "$mp.eligibility")
        enum(eligibility, "status", "$mp.eligibility", listOf("eligible", "partial", "ineligible"))
        if (eligibility["missing"] != null) {
            val missing = array(eligibility["missing"], "$mp.eligibility.missing", CapsuleLimits.MAX_MISSING)
            for (i in missing.indices) {
                val path = "$mp.eligibility.missing[$i]"
                val item = obj(missing[i], path)
                string(item, "dependency", path, max = 200)
                string(item, "reason", path, max = 2000, min = 0)
                optBool(item, "blocking", path)
            }
        }

        if (m["integrations"] != null) {
            val integrations = array(m["integrations"], "$mp.integrations", CapsuleLimits.MAX_INTEGRATIONS)
            for (i in integrations.indices) {
                val v = integrations[i]
                if (v !is String || v.isEmpty() || v.length > 100) {
                    fail("$mp.integrations[$i]", "must be a non-empty string of at most 100 characters")
                }
            }
        }

        optObj(m, "stats", mp)?.let { stats ->
            optInt(stats, "eventCount", "$mp.stats", min = 0)
            optInt(stats, "droppedEvents", "$mp.stats", min = 0)
            if (stats["checkpointKind"] != null) enum(stats, "checkpointKind", "$mp.stats", listOf("launch", "periodic"))
        }

        optObj(m, "labels", mp)?.let { labels ->
            stringMap(labels, "$mp.labels", maxEntries = CapsuleLimits.MAX_LABELS, max = 2000)
            for (k in labels.keys) if (k.length > 100) fail("$mp.labels", "keys must be at most 100 characters")
        }

        optObj(m, "redaction", mp)?.let { redaction ->
            for (k in listOf("extraKeys", "extraHeaders")) {
                if (redaction[k] == null) continue
                val list = array(redaction[k], "$mp.redaction.$k", CapsuleLimits.MAX_REDACTION_KEYS)
                for (i in list.indices) {
                    val v = list[i]
                    if (v !is String || v.length > 200) fail("$mp.redaction.$k[$i]", "must be a string")
                }
            }
        }

        optObj(m, "runtime", mp)?.let { runtime ->
            string(runtime, "name", "$mp.runtime", max = 50)
            optString(runtime, "version", "$mp.runtime", max = 200)
            optString(runtime, "language", "$mp.runtime", max = 50)
            if (runtime["frameworks"] != null) {
                val list = array(runtime["frameworks"], "$mp.runtime.frameworks", CapsuleLimits.MAX_FRAMEWORKS)
                for (i in list.indices) {
                    val v = list[i]
                    if (v !is String || v.isEmpty() || v.length > 50) {
                        fail("$mp.runtime.frameworks[$i]", "must be a non-empty string of at most 50 characters")
                    }
                }
            }
        }
        optObj(m, "capabilities", mp)?.let { caps ->
            stringMap(caps, "$mp.capabilities", maxEntries = CapsuleLimits.MAX_CAPABILITIES, max = 100)
            for (k in caps.keys) if (k.length > 100) fail("$mp.capabilities", "keys must be at most 100 characters")
        }

        // capsule-v2 §2: required interaction dependencies.
        val dependencies = if (specVersion >= 2) validateDependencies(m["dependencies"], "$mp.dependencies") else null

        val cp = "$.checkpoint"
        val checkpoint = obj(doc["checkpoint"], cp)
        enum(checkpoint, "kind", cp, listOf("launch", "periodic"))
        int(checkpoint, "t", cp)
        int(checkpoint, "wallClockMs", cp)
        optInt(checkpoint, "afterSeq", cp, min = 0)
        val checkpointData = optObj(checkpoint, "data", cp)
        if (dependencies != null && checkpointData != null) validateOpenInteractions(checkpointData, "$cp.data")

        val events = array(doc["events"], "$.events", CapsuleLimits.MAX_EVENTS)
        var lastSeq: Long? = null
        for (i in events.indices) {
            val path = "$.events[$i]"
            val e = obj(events[i], path)
            val seq = int(e, "seq", path)
            if (lastSeq != null && seq <= lastSeq) fail("$path.seq", "must be greater than the previous seq")
            lastSeq = seq
            int(e, "t", path)
            val type = string(e, "type", path, max = 100)
            val data = optObj(e, "data", path) ?: emptyMap()
            validateEventData(type, data, "$path.data")
            if (dependencies != null) validateInteraction(type, data, "$path.data", dependencies)
        }
    }

    private fun validateDependencies(value: Any?, path: String): Set<String> {
        val list = array(value, path, CapsuleLimits.MAX_DEPENDENCIES)
        val ids = HashSet<String>()
        for (i in list.indices) {
            val p = "$path[$i]"
            val d = obj(list[i], p)
            val id = string(d, "id", p, max = 100)
            if (!ids.add(id)) fail("$p.id", "must be unique")
            string(d, "kind", p, max = 50)
            string(d, "adapter", p, max = 200)
            string(d, "adapterVersion", p, max = 100)
            int(d, "schema", p, min = 1)
            if (d["required"] !is Boolean) fail("$p.required", "must be a boolean")
            enum(d, "coverage", p, listOf("complete", "partial", "diagnostic"))
            if (d["operations"] != null) {
                val ops = array(d["operations"], "$p.operations", CapsuleLimits.MAX_DEPENDENCY_ITEMS)
                for (j in ops.indices) {
                    val v = ops[j]
                    if (v !is String || v.isEmpty() || v.length > 200) fail("$p.operations[$j]", "must be a non-empty string")
                }
            }
            if (d["tested"] != null) {
                val tested = array(d["tested"], "$p.tested", CapsuleLimits.MAX_TESTED)
                for (j in tested.indices) {
                    val t = obj(tested[j], "$p.tested[$j]")
                    string(t, "package", "$p.tested[$j]", max = 200)
                    string(t, "version", "$p.tested[$j]", max = 100)
                }
            }
            if (d["gaps"] != null) {
                val gaps = array(d["gaps"], "$p.gaps", CapsuleLimits.MAX_DEPENDENCY_ITEMS)
                for (j in gaps.indices) {
                    val g = obj(gaps[j], "$p.gaps[$j]")
                    string(g, "code", "$p.gaps[$j]", max = 100)
                    optInt(g, "count", "$p.gaps[$j]", min = 0)
                    optString(g, "detail", "$p.gaps[$j]", max = 2000)
                }
            }
            optObj(d, "stats", p)?.let { stats ->
                for (k in listOf("events", "payloadBytes", "dropped", "truncated")) optInt(stats, k, "$p.stats", min = 0)
            }
        }
        return ids
    }

    private fun validateOpenInteractions(data: Map<String, Any?>, path: String) {
        val interactions = optObj(data, "interactions", path) ?: return
        if (interactions["open"] == null) return
        val open = array(interactions["open"], "$path.interactions.open", CapsuleLimits.MAX_DEPENDENCY_ITEMS * 10)
        for (i in open.indices) {
            val p = "$path.interactions.open[$i]"
            val o = obj(open[i], p)
            string(o, "dep", p, max = 100)
            string(o, "type", p, max = 50)
            string(o, "id", p, max = 200)
            optBool(o, "restorable", p)
        }
    }

    private val PAYLOAD_MODES = listOf("full", "redacted", "metadata", "omitted", "truncated")

    private fun payload(d: Map<String, Any?>, key: String, path: String) {
        val v = d[key] ?: return
        val p = obj(v, "$path.$key")
        enum(p, "mode", "$path.$key", PAYLOAD_MODES)
        if (p["replayable"] !is Boolean) fail("$path.$key.replayable", "must be a boolean")
        optInt(p, "bytes", "$path.$key", min = 0)
        optString(p, "kind", "$path.$key", max = 20)
    }

    /** capsule-v2 §9: fields replay reads from interaction events. */
    private fun validateInteraction(type: String, d: Map<String, Any?>, path: String, dependencies: Set<String>) {
        if (!InteractionTypes.isInteraction(type)) return
        val dep = string(d, "dep", path, max = 100)
        if (dep !in dependencies) fail("$path.dep", "must name a declared dependency")
        for (field in listOf("payload", "args", "value", "details")) payload(d, field, path)
        fun keyed() {
            string(d, "key", path, max = 10000)
            int(d, "occurrence", path, min = 0)
        }
        if (type.startsWith("ws.")) string(d, "conn", path, max = 200)
        when (type) {
            "ws.connect" -> keyed()
            "ws.send", "ws.message" -> int(d, "index", path, min = 0)
            "ws.close" -> {
                enum(d, "initiator", path, listOf("client", "server", "unknown"))
                optInt(d, "code", path)
            }
            "platform.call" -> {
                string(d, "channel", path, max = 1000)
                string(d, "call", path, max = 200)
                enum(d, "dir", path, listOf("dart", "platform"))
                keyed()
            }
            "platform.result", "platform.error" -> {
                string(d, "channel", path, max = 1000)
                string(d, "call", path, max = 200)
            }
            "platform.listen" -> {
                string(d, "channel", path, max = 1000)
                string(d, "sub", path, max = 200)
                keyed()
            }
            "platform.event", "platform.cancel", "platform.done" -> {
                string(d, "channel", path, max = 1000)
                string(d, "sub", path, max = 200)
            }
            "native.call" -> {
                string(d, "op", path, max = 200)
                keyed()
            }
            "native.result" -> string(d, "op", path, max = 200)
            "native.subscribe" -> {
                string(d, "sub", path, max = 200)
                keyed()
            }
            "native.event", "native.cancel", "native.done" -> string(d, "sub", path, max = 200)
        }
    }

    private fun validateFailure(f: Map<String, Any?>, path: String) {
        enum(f, "kind", path, listOf("exception", "assertion", "manual"))
        string(f, "signature", path, max = 1000)
        string(f, "type", path, max = 1000, min = 0)
        optString(f, "message", path, max = 10000)
        optString(f, "stack", path, max = 20000)
        optString(f, "assertionId", path, max = 200)
        optInt(f, "t", path)
    }

    private fun validateEventData(type: String, d: Map<String, Any?>, path: String) {
        when (type) {
            "http" -> {
                string(d, "key", path, max = 10000)
                optInt(d, "occurrence", path, min = 0)
                optInt(d, "status", path)
                optInt(d, "durationMs", path, min = 0)
                optString(d, "bodyEncoding", path, max = 20)
                optString(d, "statusMessage", path, max = 1000)
                optObj(d, "responseHeaders", path)?.let { stringMap(it, "$path.responseHeaders") }
                optObj(d, "error", path)
            }
            "pointer" -> {
                string(d, "phase", path, max = 20)
                int(d, "pointer", path)
                for (k in listOf("x", "y")) {
                    val v = d[k]
                    if (v !is Number || !v.toDouble().isFinite()) fail("$path.$k", "must be a number")
                }
                optInt(d, "buttons", path)
            }
            "action" -> {
                string(d, "id", path, max = 200)
                optObj(d, "payload", path)
            }
            "text" -> {
                string(d, "id", path, max = 200)
                optString(d, "value", path, max = 100000)
            }
            "lifecycle" -> string(d, "state", path, max = 20)
        }
    }
}
