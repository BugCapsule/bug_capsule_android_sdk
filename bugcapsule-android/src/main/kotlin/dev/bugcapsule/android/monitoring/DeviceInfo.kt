package dev.bugcapsule.android.monitoring

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

/** `contexts.device` / `os` / `app` / `culture` of this device (monitoring-v1 §2), computed once. */
internal class DeviceInfo(private val context: Context, private val app: AppIdentity) {
    private val static: Map<String, Map<String, Any?>> by lazy { compute() }

    fun contexts(): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap(static)
        out["culture"] = linkedMapOf("locale" to Locale.getDefault().toLanguageTag(), "timezone" to TimeZone.getDefault().id)
        return out
    }

    private fun compute(): Map<String, Map<String, Any?>> {
        val metrics = context.resources.displayMetrics
        val device = linkedMapOf<String, Any?>(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "arch" to Build.SUPPORTED_ABIS.firstOrNull(),
            "simulator" to isEmulator(),
        )
        val os = linkedMapOf<String, Any?>("name" to "Android", "version" to Build.VERSION.RELEASE, "build" to Build.ID, "apiLevel" to Build.VERSION.SDK_INT)
        val appCtx = linkedMapOf<String, Any?>("appId" to app.id, "appVersion" to app.version, "appBuild" to app.build)
        if (app.name != null) appCtx["appName"] = app.name
        val screen = linkedMapOf<String, Any?>(
            "widthPixels" to metrics.widthPixels,
            "heightPixels" to metrics.heightPixels,
            "density" to metrics.density.toDouble(),
        )
        return linkedMapOf("device" to device, "os" to os, "app" to appCtx, "screen" to screen)
    }

    companion object {
        const val DEBUG_META_ASSET = "bugcapsule-debug-meta.properties"

        fun isEmulator(): Boolean {
            val fp = Build.FINGERPRINT ?: ""
            return fp.startsWith("generic") || fp.contains("emulator") || fp.contains("/sdk_") ||
                Build.HARDWARE == "goldfish" || Build.HARDWARE == "ranchu" ||
                (Build.PRODUCT ?: "").let { it.startsWith("sdk") || it.contains("emulator") || it.contains("simulator") }
        }

        /** `proguardUuid` written into the APK assets by the `dev.bugcapsule.gradle` plugin. */
        fun proguardUuid(context: Context): String? = try {
            context.assets.open(DEBUG_META_ASSET).use { input ->
                Properties().apply { load(input) }.getProperty("proguardUuid")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
