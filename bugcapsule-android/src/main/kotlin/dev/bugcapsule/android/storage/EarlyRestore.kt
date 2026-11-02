package dev.bugcapsule.android.storage

import android.content.Context
import dev.bugcapsule.android.core.J

/**
 * Restores checkpointed storage before the app reads it (runtimes-v1 §7):
 * called by the replay runner in `Instrumentation.onCreate`, before
 * `Application.onCreate`. Attempt isolation (`pm clear`) guarantees the stores
 * start empty, so only captured keys are written.
 *
 * A capsule is untrusted input, so only what the capture-time
 * [StoragePolicy] (manifest `storagePolicy`) allowed is written, and, when
 * the runner passes the app's current policy, only what that allows too.
 * Names must be plain file names that stay inside `shared_prefs` /
 * `files/datastore`, and BugCapsule's own files are never written. Every
 * refused file or key is a `restore.disallowed` divergence; a capsule
 * without a policy restores no storage at all.
 */
object EarlyRestore {
    /** Checkpoint keys restored here (not by an integration's `restore`). */
    val KEYS = setOf(BugCapsuleSharedPreferences.NAME, BugCapsuleDataStore.NAME)

    /** Divergence type of every storage write the policy refused (material: not in `DivergencePolicy.EXPECTED`). */
    const val DISALLOWED = "restore.disallowed"

    @JvmStatic
    @JvmOverloads
    fun restore(
        context: Context,
        data: Map<String, Any?>,
        policy: StoragePolicy?,
        appPolicy: StoragePolicy? = null,
        diverge: (type: String, detail: String) -> Unit,
    ) {
        val prefs = J.obj(data[BugCapsuleSharedPreferences.NAME])
        val stores = J.obj(data[BugCapsuleDataStore.NAME])
        if (prefs.isNullOrEmpty() && stores.isNullOrEmpty()) return
        if (policy == null) {
            diverge(DISALLOWED, "capsule has no storagePolicy; no storage restored")
            return
        }
        val gate = Gate(policy, appPolicy, diverge)
        prefs?.let { files ->
            try {
                restoreSharedPreferences(context, files, gate, diverge)
            } catch (e: Throwable) {
                diverge("restore.shared_preferences", e.toString())
            }
        }
        stores?.let { all ->
            try {
                for ((name, entries) in all) {
                    val map = J.obj(entries) ?: continue
                    val file = gate.file(BugCapsuleDataStore.NAME, name) { DataStoreFiles.file(context, it) } ?: continue
                    val allowed = gate.keys(BugCapsuleDataStore.NAME, name, map)
                    if (allowed.isEmpty()) continue
                    reportPlaceholders(name, allowed, diverge)
                    for (key in DataStoreFiles.write(file, allowed)) {
                        diverge("storage.unknownType", "$name/$key")
                    }
                }
            } catch (e: Throwable) {
                // Includes NoClassDefFoundError when the app has no DataStore.
                diverge("restore.datastore", e.toString())
            }
        }
    }

    /** Applies the capture-time and current policies; reports what it refuses. */
    private class Gate(
        val policy: StoragePolicy,
        val appPolicy: StoragePolicy?,
        val diverge: (String, String) -> Unit,
    ) {
        fun <F> file(kind: String, name: String, resolve: (String) -> F?): F? {
            if (StorageNames.isInternal(name)) {
                diverge(DISALLOWED, "$kind $name: BugCapsule's own storage")
                return null
            }
            val f = resolve(name)
            if (f == null) diverge(DISALLOWED, "$kind ${name.take(200)}: not a plain file name")
            return f
        }

        fun keys(kind: String, name: String, entries: Map<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((key, raw) in entries) {
                when {
                    !policy.allows(kind, name, key) -> diverge(DISALLOWED, "$kind $name/$key: not allowed by the capture policy")
                    appPolicy != null && !appPolicy.allows(kind, name, key) ->
                        diverge(DISALLOWED, "$kind $name/$key: not allowed by the app's current policy")
                    else -> out[key] = raw
                }
            }
            return out
        }
    }

    private fun reportPlaceholders(file: String, entries: Map<String, Any?>, diverge: (String, String) -> Unit) {
        for ((key, raw) in entries) {
            val item = J.obj(raw)
            if (item == null || StorageValues.containsPlaceholder(item["value"])) diverge("storage.redacted", "$file/$key")
        }
    }

    private fun restoreSharedPreferences(context: Context, files: Map<String, Any?>, gate: Gate, diverge: (String, String) -> Unit) {
        val dir = StorageNames.sharedPrefsDir(context)
        for ((file, rawEntries) in files) {
            val all = J.obj(rawEntries) ?: continue
            gate.file(BugCapsuleSharedPreferences.NAME, file) { StorageNames.resolve(dir, it, ".xml") } ?: continue
            val entries = gate.keys(BugCapsuleSharedPreferences.NAME, file, all)
            if (entries.isEmpty()) continue
            reportPlaceholders(file, entries, diverge)
            val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            for ((key, raw) in entries) {
                val item = J.obj(raw) ?: continue
                val value = item["value"]
                try {
                    when (item["type"]) {
                        "bool" -> editor.putBoolean(key, value as Boolean)
                        "int" -> editor.putInt(key, (value as Number).toInt())
                        "long" -> editor.putLong(key, (value as Number).toLong())
                        "float", "double" -> editor.putFloat(key, (value as Number).toFloat())
                        "string" -> editor.putString(key, value as String)
                        "stringSet" -> editor.putStringSet(key, (value as List<*>).map { it.toString() }.toSet())
                        else -> diverge("storage.unknownType", "$file/$key")
                    }
                } catch (e: ClassCastException) {
                    diverge("restore.shared_preferences", "$file/$key: $e")
                }
            }
            if (!editor.commit()) diverge("restore.shared_preferences", "$file: commit failed")
        }
    }
}
