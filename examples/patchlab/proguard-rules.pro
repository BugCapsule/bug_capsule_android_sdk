# Lab only: telemetry runs record the default uncaught-exception handler's class,
# and the acceptance harness requires it to be exactly the SDK's crash handler.
# Keeping the name changes nothing else (no members are kept).
-keepnames class dev.bugcapsule.android.internal.CrashHandler
