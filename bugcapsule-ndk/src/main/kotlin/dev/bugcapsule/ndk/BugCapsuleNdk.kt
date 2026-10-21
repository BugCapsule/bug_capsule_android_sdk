package dev.bugcapsule.ndk

import java.io.File

/**
 * Native (NDK) crash handler.
 *
 * A signal handler in `libbugcapsule-ndk.so` writes a small text crash record
 * (format `bugcapsule-native-crash 1`) to `<crashDir>/<pid>-<epochMs>.crash`
 * when the process dies from SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL or
 * SIGTRAP, then hands the signal to the previously installed handler so the
 * system crash reporting still runs. The core SDK reads, reports and deletes
 * those files on the next launch.
 *
 * The core SDK calls this object by reflection only; keep the signatures stable.
 */
object BugCapsuleNdk {
    private const val LIBRARY = "bugcapsule-ndk"

    private val lock = Any()

    @Volatile
    private var installed = false

    /** Guarded by [lock]: null = not tried yet. */
    private var libraryLoaded: Boolean? = null

    /**
     * Loads `libbugcapsule-ndk.so` and installs the signal handlers; crash
     * records go to `<crashDir>/<pid>-<epochMs>.crash`. Returns false (never
     * throws) when the library cannot load or the install fails. Idempotent.
     */
    @JvmStatic
    fun install(crashDir: String): Boolean = synchronized(lock) {
        if (installed) return true
        try {
            if (!loadLibrary()) return false
            val dir = File(crashDir)
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val record = File(dir, "${android.os.Process.myPid()}-${System.currentTimeMillis()}.crash")
            installed = nativeInstall(record.absolutePath)
            installed
        } catch (t: Throwable) {
            false
        }
    }

    /** Restores the previous signal handlers. No-op when not installed. */
    @JvmStatic
    fun uninstall() {
        synchronized(lock) {
            if (!installed) return
            try {
                nativeUninstall()
            } catch (t: Throwable) {
                // Library vanished: nothing to restore.
            }
            installed = false
        }
    }

    @JvmStatic
    val isInstalled: Boolean
        get() = installed

    /**
     * Demo/testing only: 0 = SIGSEGV (null write), 1 = abort() (SIGABRT).
     * Does nothing when the native library is not available.
     */
    @JvmStatic
    fun crashForTesting(kind: Int) {
        val loaded = synchronized(lock) {
            try {
                loadLibrary()
            } catch (t: Throwable) {
                false
            }
        }
        if (loaded) nativeCrash(kind)
    }

    private fun loadLibrary(): Boolean {
        libraryLoaded?.let { return it }
        val loaded = try {
            System.loadLibrary(LIBRARY)
            true
        } catch (t: Throwable) {
            false
        }
        libraryLoaded = loaded
        return loaded
    }

    @JvmStatic
    private external fun nativeInstall(path: String): Boolean

    @JvmStatic
    private external fun nativeUninstall()

    @JvmStatic
    private external fun nativeCrash(kind: Int)
}
