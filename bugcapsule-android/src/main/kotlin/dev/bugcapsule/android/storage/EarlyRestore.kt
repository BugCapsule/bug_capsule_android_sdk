package dev.bugcapsule.android.storage

import android.content.Context
import dev.bugcapsule.android.core.J

/**
 * Restores checkpointed storage before the app reads it (runtimes-v1 §7):
 * called by the replay runner in `Instrumentation.onCreate`, before
 * `Application.onCreate`. Attempt isolation (`pm clear`) guarantees the stores
 * start empty, so only captured keys are written.
 */
object EarlyRestore {
    /** Checkpoint keys restored here (not by an integration's `restore`). */
    val KEYS = setOf(BugCapsuleSharedPreferences.NAME, BugCapsuleDataStore.NAME)

    @JvmStatic
    fun restore(context: Context, data: Map<String, Any?>, diverge: (type: String, detail: String) -> Unit) {
        J.obj(data[BugCapsuleSharedPreferences.NAME])?.let { files ->
            try {
                restoreSharedPreferences(context, files, diverge)
            } catch (e: Throwable) {
                diverge("restore.shared_preferences", e.toString())
            }
        }
        J.obj(data[BugCapsuleDataStore.NAME])?.let { stores ->
            try {
                for ((name, entries) in stores) {
                    val map = J.obj(entries) ?: continue
                    reportPlaceholders(name, map, diverge)
                    for (key in DataStoreFiles.write(DataStoreFiles.file(context, name), map)) {
                        diverge("storage.unknownType", "$name/$key")
                    }
                }
            } catch (e: Throwable) {
                // Includes NoClassDefFoundError when the app has no DataStore.
                diverge("restore.datastore", e.toString())
            }
        }
    }

    private fun reportPlaceholders(file: String, entries: Map<String, Any?>, diverge: (String, String) -> Unit) {
        for ((key, raw) in entries) {
            val item = J.obj(raw)
            if (item == null || StorageValues.containsPlaceholder(item["value"])) diverge("storage.redacted", "$file/$key")
        }
    }

    private fun restoreSharedPreferences(context: Context, files: Map<String, Any?>, diverge: (String, String) -> Unit) {
        for ((file, rawEntries) in files) {
            val entries = J.obj(rawEntries) ?: continue
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
