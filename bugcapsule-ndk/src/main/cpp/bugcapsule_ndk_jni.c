/*
 * JNI bindings of dev.bugcapsule.ndk.BugCapsuleNdk onto the core handler
 * (bugcapsule_ndk.c). Kept separate so the core compiles without JNI.
 */
#include <jni.h>

#include "bugcapsule_ndk.h"

JNIEXPORT jboolean JNICALL
Java_dev_bugcapsule_ndk_BugCapsuleNdk_nativeInstall(JNIEnv *env, jclass clazz, jstring path) {
    const char *chars;
    int ok;
    (void) clazz;
    if (path == NULL) return JNI_FALSE;
    chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (chars == NULL) return JNI_FALSE;
    ok = bc_ndk_install(chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_bugcapsule_ndk_BugCapsuleNdk_nativeUninstall(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    bc_ndk_uninstall();
}

JNIEXPORT void JNICALL
Java_dev_bugcapsule_ndk_BugCapsuleNdk_nativeCrash(JNIEnv *env, jclass clazz, jint kind) {
    (void) env;
    (void) clazz;
    bc_ndk_crash((int) kind);
}
