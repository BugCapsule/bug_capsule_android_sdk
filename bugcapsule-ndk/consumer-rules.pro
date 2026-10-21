# BugCapsuleNdk is called by the core SDK through reflection and binds JNI
# functions by name: keep the class, its members and native method names.
-keep class dev.bugcapsule.ndk.BugCapsuleNdk { *; }
-keepclasseswithmembernames,includedescriptorclasses class dev.bugcapsule.ndk.** {
    native <methods>;
}
