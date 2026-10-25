package dev.bugcapsule.android.replay

/** A rejected event was not delivered. Never retry successful input or swallow a second denial. */
internal fun injectWithKeyboardRetry(
    keyboardTransition: Boolean,
    waitForTransition: () -> Boolean,
    inject: () -> Unit,
) {
    try {
        inject()
    } catch (denied: SecurityException) {
        if (!keyboardTransition || !waitForTransition()) throw denied
        inject()
    }
}
