package dev.bugcapsule.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Cached cart in SharedPreferences (allowlisted for BugCapsule checkpoints). */
object CartCache {
    const val FILE = "shop_prefs"
    const val KEY = "cart_cache"

    fun read(context: Context): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)

    fun write(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
    }

    /** v0.9 stored the cart as a JSON array of SKUs; v1.0 stores an object. */
    fun writeLegacy(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(listOf("mug", "tee")).toString())
            .putInt("schema", 9)
            .commit()
    }

    /**
     * Bug 3 (state-restoration-dependent): parses the cache as an object.
     * Only devices upgraded from v0.9 hold a legacy array here, and they crash.
     */
    fun parse(cached: String): JSONObject = if (DemoFixes.legacy && cached.trimStart().startsWith("[")) {
        JSONObject().put("items", JSONArray())
    } else {
        JSONObject(cached)
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = SettingsStore.NAME)

/** Preferences DataStore (allowlisted): display currency. */
object SettingsStore {
    const val NAME = "settings"
    private val CURRENCY = stringPreferencesKey("currency")

    suspend fun currency(context: Context): String = context.settingsStore.data.first()[CURRENCY] ?: "EUR"

    suspend fun setCurrency(context: Context, value: String) {
        context.settingsStore.edit { it[CURRENCY] = value }
    }
}
