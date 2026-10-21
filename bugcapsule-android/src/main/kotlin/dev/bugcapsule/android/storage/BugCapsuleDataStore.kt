package dev.bugcapsule.android.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.EligibilityBuilder
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.RecordedWindow
import dev.bugcapsule.android.core.Redactor
import dev.bugcapsule.android.internal.Files
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.sink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Preferences DataStore checkpoints (`datastore`: `{name: {key: {type,
 * value}}}`) for **allowlisted** stores and key prefixes.
 *
 * The store file (`files/datastore/<name>.preferences_pb`) is read directly
 * (DataStore writes it atomically), so no second DataStore instance is ever
 * created for the app's file. Replay writes the file before
 * `Application.onCreate`, before the app opens its DataStore. Requires
 * `androidx.datastore:datastore-preferences` 1.1+ in the app.
 *
 * ```kotlin
 * BugCapsuleDataStore.builder().allow("settings").build()
 * ```
 */
class BugCapsuleDataStore private constructor(
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

    private fun isAllowed(store: String, key: String): Boolean {
        val prefixes = allow[store] ?: return false
        if (deny[store]?.any { key.startsWith(it) } == true) return false
        return prefixes.any { key.startsWith(it) }
    }

    override fun snapshot(kind: CheckpointKind): Any? {
        val s = session ?: return null
        return snapshot(s.applicationContext, s.redactor)
    }

    fun snapshot(context: Context, redactor: Redactor): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (store in allow.keys) {
            val prefs = DataStoreFiles.read(DataStoreFiles.file(context, store)) ?: emptyMap()
            val entries = LinkedHashMap<String, Any?>()
            for ((key, value) in prefs.toSortedMap()) {
                if (!isAllowed(store, key)) continue
                val type = StorageValues.typeOf(value) ?: continue
                if (redactor.isSensitiveKey(key)) {
                    redacted.add("$store/$key")
                    entries[key] = linkedMapOf("type" to type, "value" to Redactor.PLACEHOLDER)
                    continue
                }
                val clean = StorageValues.redact(value, redactor)
                if (clean != StorageValues.jsonValue(value)) redacted.add("$store/$key")
                entries[key] = linkedMapOf("type" to type, "value" to clean)
            }
            out[store] = entries
        }
        return out
    }

    override fun describeEligibility(eligibility: EligibilityBuilder, window: RecordedWindow) {
        for (key in redacted.toList()) {
            eligibility.missing("storage.$key", "value redacted on device; replay restores a placeholder")
        }
    }

    override fun capabilities() = mapOf("storage.datastore" to "supported")

    class Builder {
        private val allow = LinkedHashMap<String, MutableList<String>>()
        private val deny = LinkedHashMap<String, MutableList<String>>()

        /** Captures keys of the store [name] (`preferencesDataStore(name = ...)`) with these prefixes (all when none). */
        fun allow(name: String, vararg keyPrefixes: String) = apply {
            allow.getOrPut(name) { ArrayList() }.addAll(if (keyPrefixes.isEmpty()) listOf("") else keyPrefixes.toList())
        }

        fun deny(name: String, vararg keyPrefixes: String) = apply {
            deny.getOrPut(name) { ArrayList() }.addAll(keyPrefixes)
        }

        fun build() = BugCapsuleDataStore(allow.mapValues { it.value.toList() }, deny.mapValues { it.value.toList() })
    }

    companion object {
        const val NAME = "datastore"

        @JvmStatic
        fun builder() = Builder()
    }
}

/** Reads and writes Preferences DataStore files without a DataStore instance. */
internal object DataStoreFiles {
    fun file(context: Context, name: String) = File(context.filesDir, "datastore/$name.preferences_pb")

    fun read(file: File): Map<String, Any?>? {
        if (!file.exists()) return null
        val prefs: Preferences = runBlocking { file.source().buffer().use { PreferencesSerializer.readFrom(it) } }
        return prefs.asMap().mapKeys { it.key.name }
    }

    /** Writes [entries] (`{key: {type, value}}`) to [file] atomically. Returns keys whose type is unknown. */
    fun write(file: File, entries: Map<String, Any?>): List<String> {
        val unknown = ArrayList<String>()
        val prefs = mutablePreferencesOf()
        for ((key, raw) in entries) {
            val item = J.obj(raw) ?: continue
            val value = item["value"]
            when (item["type"]) {
                "bool" -> prefs[booleanPreferencesKey(key)] = value as Boolean
                "int" -> prefs[intPreferencesKey(key)] = (value as Number).toInt()
                "long" -> prefs[longPreferencesKey(key)] = (value as Number).toLong()
                "float" -> prefs[floatPreferencesKey(key)] = (value as Number).toFloat()
                "double" -> prefs[doublePreferencesKey(key)] = (value as Number).toDouble()
                "string" -> prefs[stringPreferencesKey(key)] = value as String
                "stringSet" -> prefs[stringSetPreferencesKey(key)] = (value as List<*>).map { it.toString() }.toSet()
                else -> unknown.add(key)
            }
        }
        val bytes = ByteArrayOutputStream()
        runBlocking { bytes.sink().buffer().use { PreferencesSerializer.writeTo(prefs, it) } }
        Files.writeAtomically(file, bytes.toByteArray())
        return unknown
    }
}
