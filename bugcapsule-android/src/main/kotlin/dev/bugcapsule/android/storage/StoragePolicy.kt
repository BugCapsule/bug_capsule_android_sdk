package dev.bugcapsule.android.storage

import android.content.Context
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.core.J
import java.io.File

/**
 * Which storage files and keys capture may record, and so replay may restore
 * (manifest `storagePolicy`: `{"shared_preferences"|"datastore": {name:
 * {"allow": [keyPrefix], "deny": [keyPrefix]}}}`). Capture records the
 * effective policy of [BugCapsuleSharedPreferences] / [BugCapsuleDataStore]
 * in every capsule, because early restore runs before the app's own config
 * exists; restore writes only what that policy (and the app's current one,
 * when the replay runner passes it) allows.
 */
class StoragePolicy internal constructor(internal val stores: Map<String, Map<String, Rule>>) {
    /** Key prefixes of one file or store; deny wins over allow. */
    class Rule(val allow: List<String>, val deny: List<String>) {
        fun allows(key: String): Boolean = deny.none { key.startsWith(it) } && allow.any { key.startsWith(it) }
    }

    /** True when [kind] (`shared_preferences` / `datastore`) file [name] may hold [key]. */
    fun allows(kind: String, name: String, key: String): Boolean = stores[kind]?.get(name)?.allows(key) == true

    fun toJson(): Map<String, Any?> = stores.mapValues { (_, files) ->
        files.mapValues { (_, r) -> linkedMapOf("allow" to r.allow, "deny" to r.deny) }
    }

    override fun equals(other: Any?) = other is StoragePolicy && other.toJson() == toJson()
    override fun hashCode() = toJson().hashCode()
    override fun toString() = "StoragePolicy(${toJson()})"

    companion object {
        /** The policy of the storage integrations among [integrations]; null when there are none. */
        @JvmStatic
        fun of(integrations: List<BugCapsuleIntegration>): StoragePolicy? {
            val stores = LinkedHashMap<String, Map<String, Rule>>()
            for (i in integrations) {
                when (i) {
                    is BugCapsuleSharedPreferences -> stores[BugCapsuleSharedPreferences.NAME] = i.rules()
                    is BugCapsuleDataStore -> stores[BugCapsuleDataStore.NAME] = i.rules()
                }
            }
            return if (stores.isEmpty()) null else StoragePolicy(stores)
        }

        /** Parses a manifest `storagePolicy`; null when absent or malformed (restore then writes nothing). */
        @JvmStatic
        fun fromJson(j: Any?): StoragePolicy? {
            val obj = J.obj(j) ?: return null
            return try {
                StoragePolicy(
                    obj.mapValues { (_, files) ->
                        J.obj(files)!!.mapValues { (_, raw) ->
                            val r = J.obj(raw)!!
                            Rule(strings(r["allow"]), strings(r["deny"]))
                        }
                    },
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun strings(v: Any?): List<String> = (J.list(v) ?: emptyList()).map { it as String }

        internal fun rules(allow: Map<String, List<String>>, deny: Map<String, List<String>>): Map<String, Rule> =
            allow.mapValues { (name, prefixes) -> Rule(prefixes, deny[name] ?: emptyList()) }
    }
}

/** File names a capsule may name for restore (runtimes-v1 §7). */
internal object StorageNames {
    private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

    /** BugCapsule's own files: never restored from a capsule. */
    fun isInternal(name: String): Boolean = name.lowercase().startsWith("bugcapsule")

    /**
     * The file for [name] under [root], or null when [name] is not a plain
     * file name or the resolved file escapes [root] (e.g. `..`).
     */
    fun resolve(root: File, name: String, suffix: String): File? {
        if (!NAME.matches(name) || name == "." || name == "..") return null
        return try {
            val base = root.canonicalFile
            val file = File(base, name + suffix).canonicalFile
            file.takeIf { it.parentFile == base }
        } catch (_: Exception) {
            null
        }
    }

    fun sharedPrefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")
    fun dataStoreDir(context: Context) = File(context.filesDir, "datastore")
}
