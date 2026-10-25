package dev.bugcapsule.android.internal

import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.ConnectionSource
import dev.bugcapsule.android.ConnectionToken
import dev.bugcapsule.android.ConnectionTokenError
import dev.bugcapsule.android.ParsedConnectionToken

/** How the backend was configured, and why a token could not be used. */
internal class ResolvedConnection(
    val source: ConnectionSource?,
    val tokenError: ConnectionTokenError? = null,
    val tokenMessage: String? = null,
) {
    companion object {
        val NONE = ResolvedConnection(null)
    }
}

/**
 * Turns `token` into `backend` (connection token v1). An explicit `backend`
 * wins; an unusable token leaves no backend, so the app runs without the
 * backend instead of failing. Offline, never throws, logs at most one line.
 */
internal object ConnectionResolver {
    fun resolve(config: BugCapsuleConfig): Pair<BugCapsuleConfig, ResolvedConnection> {
        val token = config.token
        if (config.backend != null) {
            if (!token.isNullOrBlank()) SdkLog.w("both token and backend are set; using backend and ignoring token")
            return config to ResolvedConnection(ConnectionSource.BACKEND)
        }
        if (token == null) return config to ResolvedConnection.NONE
        return when (val parsed = ConnectionToken.parse(token)) {
            is ParsedConnectionToken.Valid ->
                config.toBuilder().backend(parsed.backend).build() to ResolvedConnection(ConnectionSource.TOKEN)
            is ParsedConnectionToken.Invalid -> {
                SdkLog.e("the SDK token can't be used (${parsed.error.code}): ${parsed.message}. The app runs without BugCapsule's backend.")
                config to ResolvedConnection(ConnectionSource.TOKEN, parsed.error, parsed.message)
            }
        }
    }
}
