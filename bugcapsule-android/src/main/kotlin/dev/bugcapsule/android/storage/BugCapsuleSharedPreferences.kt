package dev.bugcapsule.android.storage

import android.content.Context
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.RecordedWindow
import dev.bugcapsule.android.core.Redactor

/**
 * SharedPreferences checkpoints: snapshots **allowlisted** files/keys into
 * every checkpoint (`shared_preferences`: `{file: {key: {type, value}}}`)
 * and restores them in replay before `Application.onCreate` (the replay
 * runner does that from the capsule; this integration declares what is
 * captured).
 *
 * ```kotlin
 * BugCapsuleSharedPreferences.builder()
 *     .allowFile("shop_prefs")                  // every key of the file
 *     .allow("settings", "onboarding_", "ui_")  // keys with these prefixes
 *     .deny("settings", "ui_token")             // deny wins over allow
 *     .build()
 * ```
 *
 * Values are redacted like events: sensitive key names keep only their type,
 * string values go through JSON/free-text redaction.
 */
class BugCapsuleSharedPreferences private constructor(
    private val allow: Map<String, List<String>>,
    private val deny: Map<String, List<String>>,
) : BugCapsuleIntegration {
    override val name: String = NAME

    @Volatile
    private var session: BugCapsuleSession? = null
    private val redacted = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    override fun setUp(session: BugCapsuleSession) {
        this.session = session
    }

    fun isAllowed(file: String, key: String): Boolean {
        val prefixes = allow[file] ?: return false
        if (deny[file]?.any { key.startsWith(it) } == true) return false
        return prefixes.any { key.startsWith(it) }
    }

    override fun snapshot(kind: CheckpointKind): Any? {
        val s = session ?: return null
        return snapshot(s.applicationContext, s.redactor)
    }

    /** The checkpoint value for [context]'s preferences (exposed for tests). */
    fun snapshot(context: Context, redactor: Redactor): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (file in allow.keys) {
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val entries = LinkedHashMap<String, Any?>()
            for ((key, value) in prefs.all.toSortedMap()) {
                if (!isAllowed(file, key)) continue
                val type = StorageValues.typeOf(value) ?: continue
                if (redactor.isSensitiveKey(key)) {
                    redacted.add("$file/$key")
                    entries[key] = linkedMapOf("type" to type, "value" to Redactor.PLACEHOLDER)
                    continue
                }
                val clean = StorageValues.redact(value, redactor)
                if (clean != StorageValues.jsonValue(value)) redacted.add("$file/$key")
                entries[key] = linkedMapOf("type" to type, "value" to clean)
            }
            out[file] = entries
        }
        return out
    }

    override fun describeEligibility(eligibility: EligibilityBuilder, window: RecordedWindow) {
        for (key in redacted.toList()) {
            eligibility.missing("storage.$key", "value redacted on device; replay restores a placeholder")
        }
    }

    override fun capabilities() = mapOf("storage.shared_preferences" to "supported")

    class Builder {
        private val allow = LinkedHashMap<String, MutableList<String>>()
        private val deny = LinkedHashMap<String, MutableList<String>>()

        /** Captures every key of [file]. */
        fun allowFile(file: String) = apply { allow.getOrPut(file) { ArrayList() }.add("") }

        /** Captures keys of [file] starting with one of [keyPrefixes]. */
        fun allow(file: String, vararg keyPrefixes: String) = apply {
            allow.getOrPut(file) { ArrayList() }.addAll(if (keyPrefixes.isEmpty()) listOf("") else keyPrefixes.toList())
        }

        /** Never captures keys of [file] starting with one of [keyPrefixes]. */
        fun deny(file: String, vararg keyPrefixes: String) = apply {
            deny.getOrPut(file) { ArrayList() }.addAll(keyPrefixes)
        }

        fun build() = BugCapsuleSharedPreferences(allow.mapValues { it.value.toList() }, deny.mapValues { it.value.toList() })
    }

    companion object {
        const val NAME = "shared_preferences"

        @JvmStatic
        fun builder() = Builder()
    }
}

/** Typed storage values (runtimes-v1 §7 shapes). */
internal object StorageValues {
    fun typeOf(value: Any?): String? = when (value) {
        is Boolean -> "bool"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is Double -> "double"
        is String -> "string"
        is Set<*> -> "stringSet"
        else -> null
    }

    fun jsonValue(value: Any?): Any? = when (value) {
        is Float -> value.toDouble()
        is Set<*> -> value.map { it.toString() }.sorted()
        else -> value
    }

    fun redact(value: Any?, redactor: Redactor): Any? = when (value) {
        is String -> redactor.redactJson(value)
        is Set<*> -> value.map { redactor.redactJson(it.toString()) }.sortedBy { it.toString() }
        else -> jsonValue(value)
    }

    fun containsPlaceholder(value: Any?): Boolean = when (value) {
        is String -> value.contains(Redactor.PLACEHOLDER)
        is List<*> -> value.any { containsPlaceholder(it) }
        is Map<*, *> -> value.values.any { containsPlaceholder(it) }
        else -> false
    }
}
