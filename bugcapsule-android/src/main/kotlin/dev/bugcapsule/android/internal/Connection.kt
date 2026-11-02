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
 * wins; an unusable token, or an explicit backend with a `bcs_` key or plain
 * http to a non-local host, leaves no backend, so the app runs without the
 * backend instead of failing. Offline, never throws, logs at most one line.
 */
internal object ConnectionResolver {
    fun resolve(config: BugCapsuleConfig): Pair<BugCapsuleConfig, ResolvedConnection> {
        val token = config.token
        val backend = config.backend
        if (backend != null) {
            if (!token.isNullOrBlank()) SdkLog.w("both token and backend are set; using backend and ignoring token")
            val refusal = ConnectionToken.explicitBackendRefusal(backend)
            if (refusal != null) {
                SdkLog.e("backend config refused: $refusal. The app runs without BugCapsule's backend.")
                return config.toBuilder().backend(null).build() to ResolvedConnection.NONE
            }
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
