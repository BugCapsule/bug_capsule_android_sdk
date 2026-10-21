package dev.bugcapsule.demo

import android.app.Application
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.EndpointRule
import dev.bugcapsule.android.storage.BugCapsuleDataStore
import dev.bugcapsule.android.storage.BugCapsuleSharedPreferences
import dev.bugcapsule.navigation.BugCapsuleNavigation
import dev.bugcapsule.okhttp.BugCapsuleOkHttp

class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val builder = BugCapsuleConfig.builder()
            .mode(CaptureMode.QA)
            .gitSha(BuildConfig.BUGCAPSULE_GIT_SHA)
            .gitDirty(if (BuildConfig.BUGCAPSULE_GIT_SHA.isEmpty()) null else BuildConfig.BUGCAPSULE_GIT_DIRTY)
            .flavor(if (DemoFixes.active.isEmpty()) "demo" else "demo+fix:" + DemoFixes.active.joinToString(","))
            .redactKeys("loyaltyId")
            .label("app", "android-demo")
            // Monitoring (errors, sessions and feedback are on by default):
            // trace every screen in this QA build and let testers shake to report.
            .tracesSampleRate(1.0)
            .enableTimeToFullDisplayTracing(false)
            .enableShakeToReport(true)
        if (BuildConfig.BUGCAPSULE_INGEST_KEY.isNotEmpty()) {
            builder.backend(BuildConfig.BUGCAPSULE_URL, BuildConfig.BUGCAPSULE_INGEST_KEY)
        }
        // Call BugCapsule.init first: replay restores storage right before Application.onCreate.
        BugCapsule.init(
            this,
            builder.build(),
            listOf(
                okHttp,
                navigation,
                BugCapsuleSharedPreferences.builder().allowFile(CartCache.FILE).build(),
                BugCapsuleDataStore.builder().allow(SettingsStore.NAME).build(),
            ),
        )
        ShopNetwork.init()
    }

    companion object {
        /** Checkout bodies carry a timestamp and a nonce that must not take part in the request key. */
        val okHttp = BugCapsuleOkHttp(
            rules = listOf(EndpointRule("POST", "/api/checkout", ignoreBodyFields = setOf("timestamp", "nonce"))),
        )
        val navigation = BugCapsuleNavigation()
    }
}

/** Build-time fix toggles (`-PbugcapsuleDemoFix=race`); see the README "Fix toggle". */
object DemoFixes {
    val active: Set<String> = BuildConfig.DEMO_FIX.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val race: Boolean get() = "race" in active
    val price: Boolean get() = "price" in active
    val legacy: Boolean get() = "legacy" in active
}
