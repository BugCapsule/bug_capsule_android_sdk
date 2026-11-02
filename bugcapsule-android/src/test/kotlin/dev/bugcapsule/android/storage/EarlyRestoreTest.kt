package dev.bugcapsule.android.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.ReplayOutcome
import dev.bugcapsule.android.core.ReplayReport
import dev.bugcapsule.android.core.ReplayStrategy
import dev.bugcapsule.android.core.AttemptStatus
import dev.bugcapsule.android.core.Verdicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A capsule is untrusted: early restore writes only what the capture policy allowed (BC-ENT-013). */
@RunWith(RobolectricTestRunner::class)
class EarlyRestoreTest {
    private lateinit var app: Application
    private val divergences = ArrayList<Divergence>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        divergences.clear()
    }

    private fun str(v: String) = mapOf("type" to "string", "value" to v)

    private val policy = StoragePolicy.of(
        listOf(
            BugCapsuleSharedPreferences.builder().allow("shop_prefs", "cart_", "ui_").deny("shop_prefs", "ui_token").build(),
            BugCapsuleDataStore.builder().allow("settings", "currency").build(),
        ),
    )!!

    private fun restore(data: Map<String, Any?>, policy: StoragePolicy? = this.policy, appPolicy: StoragePolicy? = null) =
        EarlyRestore.restore(app, data, policy, appPolicy) { type, detail -> divergences.add(Divergence(type, detail)) }

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun disallowed() = divergences.filter { it.type == EarlyRestore.DISALLOWED }

    @Test
    fun allowedKeysAreRestoredAndEverythingElseIsRefusedAndReported() {
        restore(
            mapOf(
                "shared_preferences" to mapOf(
                    "shop_prefs" to mapOf(
                        "cart_items" to str("mug"),
                        "ui_theme" to str("dark"),
                        "ui_token" to str("planted"), // denied
                        "admin" to str("true"), // outside the allowlist
                    ),
                    "other_prefs" to mapOf("x" to str("1")), // file not in the policy
                ),
                "datastore" to mapOf("settings" to mapOf("currency" to str("USD"), "debug_flag" to str("on"))),
            ),
        )
        assertEquals("mug", prefs("shop_prefs").getString("cart_items", null))
        assertEquals("dark", prefs("shop_prefs").getString("ui_theme", null))
        assertFalse(prefs("shop_prefs").contains("ui_token"))
        assertFalse(prefs("shop_prefs").contains("admin"))
        assertFalse(prefs("other_prefs").contains("x"))
        val ds = DataStoreFiles.read(DataStoreFiles.file(app, "settings")!!)!!
        assertEquals("USD", ds["currency"])
        assertFalse(ds.containsKey("debug_flag"))
        val details = disallowed().map { it.detail }
        assertTrue(details.toString(), details.any { it.contains("shop_prefs/ui_token") })
        assertTrue(details.toString(), details.any { it.contains("shop_prefs/admin") })
        assertTrue(details.toString(), details.any { it.contains("other_prefs/x") })
        assertTrue(details.toString(), details.any { it.contains("settings/debug_flag") })
        assertEquals(4, details.size)
    }

    @Test
    fun traversalNamesNeverLeaveTheStorageDirectories() {
        val escape = File(app.filesDir, "x.preferences_pb")
        val outsidePrefs = File(app.filesDir.parentFile, "evil.xml")
        val names = listOf("../../x", "../x", "..", ".", "a/b", "", "x".repeat(101), "settings/../../x", "bad\u0000name")
        val wide = StoragePolicy(
            mapOf(
                "shared_preferences" to names.associateWith { StoragePolicy.Rule(listOf(""), emptyList()) },
                "datastore" to names.associateWith { StoragePolicy.Rule(listOf(""), emptyList()) },
            ),
        )
        val data = names.associateWith { mapOf("k" to str("v")) }
        restore(mapOf("shared_preferences" to data, "datastore" to data), wide)
        assertFalse(escape.exists())
        assertFalse(outsidePrefs.exists())
        assertFalse(File(app.filesDir.parentFile?.parentFile, "x.preferences_pb").exists())
        assertEquals(divergences.toString(), names.size * 2, disallowed().size)
        assertTrue(disallowed().all { it.detail.contains("not a plain file name") })
        assertNull(DataStoreFiles.file(app, "../../x"))
        assertTrue(DataStoreFiles.file(app, "settings")!!.path.endsWith("files/datastore/settings.preferences_pb"))
    }

    @Test
    fun bugCapsulesOwnStorageIsNeverWritten() {
        val wide = StoragePolicy(
            mapOf(
                "shared_preferences" to mapOf("bugcapsule_internal" to StoragePolicy.Rule(listOf(""), emptyList())),
                "datastore" to mapOf("BugCapsule.state" to StoragePolicy.Rule(listOf(""), emptyList())),
            ),
        )
        restore(
            mapOf(
                "shared_preferences" to mapOf("bugcapsule_internal" to mapOf("k" to str("v"))),
                "datastore" to mapOf("BugCapsule.state" to mapOf("k" to str("v"))),
            ),
            wide,
        )
        assertFalse(prefs("bugcapsule_internal").contains("k"))
        assertFalse(DataStoreFiles.file(app, "BugCapsule.state")!!.exists())
        assertEquals(2, disallowed().size)
        assertTrue(disallowed().all { it.detail.contains("BugCapsule's own storage") })
    }

    @Test
    fun aCapsuleWithoutAPolicyRestoresNothing() {
        restore(
            mapOf(
                "shared_preferences" to mapOf("shop_prefs" to mapOf("cart_items" to str("mug"))),
                "datastore" to mapOf("settings" to mapOf("currency" to str("USD"))),
            ),
            policy = null,
        )
        assertFalse(prefs("shop_prefs").contains("cart_items"))
        assertFalse(DataStoreFiles.file(app, "settings")!!.exists())
        assertEquals(1, disallowed().size)
        assertTrue(disallowed().single().detail.contains("no storagePolicy"))
        // A malformed policy is the same as none.
        assertNull(StoragePolicy.fromJson(mapOf("shared_preferences" to "nope")))
    }

    @Test
    fun theAppsCurrentPolicyCanOnlyNarrowTheCapsules() {
        val current = StoragePolicy.of(listOf(BugCapsuleSharedPreferences.builder().allow("shop_prefs", "cart_").build()))!!
        restore(
            mapOf("shared_preferences" to mapOf("shop_prefs" to mapOf("cart_items" to str("mug"), "ui_theme" to str("dark")))),
            appPolicy = current,
        )
        assertEquals("mug", prefs("shop_prefs").getString("cart_items", null))
        assertFalse(prefs("shop_prefs").contains("ui_theme"))
        assertTrue(disallowed().single().detail.contains("app's current policy"))
    }

    @Test
    fun withoutStorageInTheCheckpointNothingIsReported() {
        restore(emptyMap(), policy = null)
        assertTrue(divergences.isEmpty())
    }

    @Test
    fun thePolicyRoundTripsThroughJson() {
        assertEquals(policy, StoragePolicy.fromJson(policy.toJson()))
    }

    @Test
    fun aDisallowedRestoreMakesTheAttemptInvalidNotClean() {
        val clean = ReplayReport(
            outcome = ReplayOutcome.NOT_REPRODUCED,
            attempt = 1,
            durationMs = 10,
            strategy = ReplayStrategy.AUTO,
            expectedSignature = "assertion:x:",
        )
        assertEquals(AttemptStatus.CLEAN, Verdicts.assess(clean).status)
        val refused = clean.copy(divergences = listOf(Divergence(EarlyRestore.DISALLOWED, "shared_preferences shop_prefs/admin")))
        val assessed = Verdicts.assess(refused)
        assertEquals(AttemptStatus.INVALID, assessed.status)
        assertTrue(assessed.reasons.toString(), assessed.reasons.any { it.contains(EarlyRestore.DISALLOWED) })
    }
}
