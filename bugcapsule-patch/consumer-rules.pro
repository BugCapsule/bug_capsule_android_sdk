# JNI resolves these by name; R8 must not rename them.
-keep class dev.bugcapsule.patch.NativeBridge { native <methods>; }
