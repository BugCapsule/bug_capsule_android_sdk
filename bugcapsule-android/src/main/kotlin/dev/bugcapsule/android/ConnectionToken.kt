package dev.bugcapsule.android

import dev.bugcapsule.android.core.Json
import java.math.BigInteger
import java.net.URI

/** Why a connection token can't be used (connection token v1). [code] is the cross-SDK error code. */
enum class ConnectionTokenError(val code: String) {
    /** Empty, not a token (e.g. a bare `bci_`/`bct_`/`bcs_` key), damaged, or without a valid API URL / ingest credential. */
    INVALID_TOKEN("invalid_token"),

    /** `bcsdk.vN.` with a version this SDK does not understand. */
    UNSUPPORTED_TOKEN_VERSION("unsupported_token_version"),
}

/** Result of [ConnectionToken.parse]. */
sealed class ParsedConnectionToken {
    /** The token's API URL and ingest credential, exactly the explicit backend configuration. */
    class Valid(val backend: BugCapsuleBackendConfig) : ParsedConnectionToken()

    /** [message] says what to do about it (one line, safe to log). */
    class Invalid(val error: ConnectionTokenError, val message: String) : ParsedConnectionToken()
}

/**
 * SDK connection token v1: `bcsdk.v1.<base64url JSON {"api": …, "key": …}>`.
 * One string from the BugCapsule dashboard carries the API URL and the
 * ingest-only credential (the project follows from the credential). The token
 * is client-visible and grants uploads only, like an ingest key.
 *
 * Parsing is offline and never throws. Shared test vectors:
 * `spec-fixtures/connection-token-v1/vectors.json`.
 */
object ConnectionToken {
    /** The token version this SDK understands. */
    const val SUPPORTED_VERSION = 1

    private const val PREFIX = "bcsdk."
    private val TOKEN = Regex("^bcsdk\\.v(\\d+)\\.(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val KEY = Regex("^(bct|bci)_[A-Za-z0-9_-]{16,200}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+={0,2}$")
    private const val DAMAGED = "the token is damaged or incomplete; copy it again from the dashboard"

    /** Parses [token] (surrounding whitespace is ignored). */
    @JvmStatic
    fun parse(token: String?): ParsedConnectionToken {
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) return invalid("the token is empty; copy it from the BugCapsule dashboard (Project settings → Keys → SDK tokens)")
        if (t.startsWith("bci_") || t.startsWith("bct_")) {
            return invalid(
                "this is an ingest key, not a connection token: pass it with BugCapsuleConfig.builder().backend(url, ingestKey), " +
                    "or generate an SDK token in the dashboard",
            )
        }
        if (t.startsWith("bcs_")) {
            return invalid("this is a secret key: never ship it in an app. Rotate it and generate an SDK token in the dashboard")
        }
        val m = TOKEN.matchEntire(t)
            ?: return invalid("a connection token starts with \"${PREFIX}v$SUPPORTED_VERSION.\"; copy the whole token from the dashboard")
        val version = m.groupValues[1]
        if (BigInteger(version) != BigInteger.valueOf(SUPPORTED_VERSION.toLong())) {
            return ParsedConnectionToken.Invalid(
                ConnectionTokenError.UNSUPPORTED_TOKEN_VERSION,
                "token version $version needs a newer bugcapsule-android (this one understands v$SUPPORTED_VERSION)",
            )
        }
        val bytes = decodeBase64Url(m.groupValues[2]) ?: return invalid(DAMAGED)
        val payload = try {
            Json.parseUtf8(bytes)
        } catch (_: Exception) {
            return invalid(DAMAGED)
        }
        if (payload !is Map<*, *>) return invalid("the token is damaged; copy it again from the dashboard")
        val key = payload["key"]
        if (key !is String || !KEY.matches(key)) return invalid("the token has no valid ingest credential; generate a new token")
        val url = apiUrl(payload["api"]) ?: return invalid("the token has no valid API URL; generate a new token")
        return ParsedConnectionToken.Valid(BugCapsuleBackendConfig(url, key))
    }

    private fun invalid(message: String) = ParsedConnectionToken.Invalid(ConnectionTokenError.INVALID_TOKEN, message)

    /** An absolute http(s) URL without user info, query or fragment; trailing slashes removed. */
    private fun apiUrl(api: Any?): String? {
        if (api !is String || api.isEmpty() || api.length > 2000) return null
        val uri = try {
            URI(api)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.rawAuthority
        if (uri.isOpaque || authority.isNullOrEmpty() || authority.contains('@')) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val url = api.trimEnd('/')
        return url.takeIf { it.length > scheme.length + 3 }
    }

    /**
     * RFC 4648 §5 without padding (padding accepted). Hand-written: minSdk 23
     * has no `java.util.Base64`, and `android.util.Base64` is not on the JVM.
     */
    internal fun decodeBase64Url(s: String): ByteArray? {
        if (!BASE64URL.matches(s)) return null
        val body = s.trimEnd('=')
        if (body.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(body.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> return null
            }
            buffer = ((buffer shl 6) or v) and 0xffff // at most 13 pending bits
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
