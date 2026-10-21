package dev.bugcapsule.android.internal

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.bugcapsule.android.replay.ScreenInfo
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android facts and threads the runtime needs (one place to fake in tests). */
internal class Host(val application: Application) {
    val context: Context get() = application
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var currentWindow: WeakReference<android.view.Window>? = null

    val isDebuggable: Boolean get() = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** `noBackupFilesDir/bugcapsule` (app-private, excluded from backups). */
    val storageRoot: File get() = File(application.noBackupFilesDir, "bugcapsule")

    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    val isMainThread: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    fun postToMain(task: Runnable) {
        main.post(task)
    }

    fun postToMainDelayed(delayMs: Long, task: Runnable) {
        main.postDelayed(task, delayMs)
    }

    fun removeFromMain(task: Runnable) = main.removeCallbacks(task)

    /** Runs [task] on the main thread and waits up to [timeoutMs]; false on timeout. */
    fun runOnMainSync(timeoutMs: Long, task: Runnable): Boolean {
        if (isMainThread) {
            task.run()
            return true
        }
        val done = CountDownLatch(1)
        main.post {
            try {
                task.run()
            } finally {
                done.countDown()
            }
        }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun screen(): ScreenInfo {
        val metrics = application.resources.displayMetrics
        val density = metrics.density.toDouble().takeIf { it > 0 } ?: 1.0
        val decor = currentWindow?.get()?.peekDecorView()
        val w = decor?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val h = decor?.height?.takeIf { it > 0 } ?: metrics.heightPixels
        return ScreenInfo(round2(w / density), round2(h / density), density)
    }

    fun osVersion(): String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun apiLevel(): Int = Build.VERSION.SDK_INT

    fun locale(): String = Locale.getDefault().toLanguageTag()

    data class Package(val id: String, val version: String, val build: String, val name: String?)

    fun packageInfo(): Package {
        val id = application.packageName
        return try {
            val info = application.packageManager.getPackageInfo(id, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val label = try {
                application.applicationInfo.loadLabel(application.packageManager).toString()
            } catch (_: Exception) {
                null
            }
            Package(id, info.versionName ?: "0", code.toString(), label)
        } catch (_: PackageManager.NameNotFoundException) {
            Package(id, "0", "0", null)
        }
    }

    /** `<meta-data android:name="dev.bugcapsule.GIT_SHA" .../>` fallback for the build SHA. */
    fun metaData(name: String): String? = try {
        val info = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
        info.metaData?.get(name)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun round2(v: Double) = Math.round(v * 100) / 100.0
    }
}
