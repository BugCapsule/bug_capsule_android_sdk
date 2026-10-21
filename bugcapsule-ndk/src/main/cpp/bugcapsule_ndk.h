/*
 * BugCapsule NDK crash handler: core API (no JNI).
 *
 * The JNI glue lives in bugcapsule_ndk_jni.c; this header is also what the
 * host smoke test (src/test/native) links against.
 */
#ifndef BUGCAPSULE_NDK_H
#define BUGCAPSULE_NDK_H

#ifdef __cplusplus
extern "C" {
#endif

/* Maximum length (including NUL) of the crash record path. */
#define BC_NDK_MAX_PATH 1024

/*
 * Installs the signal handlers. Crash records are written to record_path
 * (copied into a static buffer). Returns 1 on success, 0 on failure.
 * Idempotent: returns 1 if already installed (the first path is kept).
 * Not thread safe: callers serialize install/uninstall.
 */
int bc_ndk_install(const char *record_path);

/* Restores the previous signal handlers and alternate stack. */
void bc_ndk_uninstall(void);

/* 1 when the handlers are installed. */
int bc_ndk_is_installed(void);

/* Demo/testing only: 0 = SIGSEGV (null write), 1 = abort() (SIGABRT). */
void bc_ndk_crash(int kind);

#ifdef __cplusplus
}
#endif

#endif /* BUGCAPSULE_NDK_H */
