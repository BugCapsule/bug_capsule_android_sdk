package dev.bugcapsule.android

import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.SpecFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Connection token v1 parsing (spec-fixtures/connection-token-v1). Plain JVM: the parser needs no Android. */
class ConnectionTokenTest {
    private val vectors = SpecFixtures.json("connection-token-v1/vectors.json")
    private fun list(key: String) = J.list(vectors[key])!!.map { J.obj(it)!! }

    @Test
    fun parsesEveryValidVector() {
        val valid = list("valid")
        assertTrue(valid.size >= 6)
        for (v in valid) {
            when (val r = ConnectionToken.parse(v["token"] as String)) {
                is ParsedConnectionToken.Valid -> {
                    assertEquals(v["name"].toString(), v["url"], r.backend.url)
                    assertEquals(v["name"].toString(), v["ingestKey"], r.backend.ingestKey)
                }
                is ParsedConnectionToken.Invalid -> fail("${v["name"]}: refused (${r.error.code}: ${r.message})")
            }
        }
    }

    @Test
    fun refusesEveryInvalidVectorWithItsCode() {
        val invalid = list("invalid")
        assertTrue(invalid.size >= 18)
        for (v in invalid) {
            when (val r = ConnectionToken.parse(v["token"] as String)) {
                is ParsedConnectionToken.Valid -> fail("${v["name"]}: accepted as ${r.backend.url}")
                is ParsedConnectionToken.Invalid -> {
                    assertEquals(v["name"].toString(), v["error"], r.error.code)
                    assertTrue(v["name"].toString(), r.message.isNotBlank())
                }
            }
        }
    }

    private fun message(token: String?) = (ConnectionToken.parse(token) as ParsedConnectionToken.Invalid).message

    @Test
    fun messagesSayWhatToDo() {
        assertTrue(message(null).contains("empty"))
        assertTrue(message("   ").contains("empty"))
        assertTrue(message("bci_Q2xpZW50VmlzaWJsZUluZ2VzdE9ubHlLZXlfMDEyMzQ").contains("ingest key, not a connection token"))
        assertTrue(message("bct_Q2xpZW50VmlzaWJsZUluZ2VzdE9ubHlLZXlfMDEyMzQ").contains("ingest key, not a connection token"))
        assertTrue(message("bcs_Q2xpZW50VmlzaWJsZUluZ2VzdE9ubHlLZXlfMDEyMzQ").contains("secret key"))
        assertTrue(message("hello").contains("bcsdk.v1."))
        assertTrue(message("bcsdk.v2.e30").contains("token version 2 needs a newer bugcapsule-android"))
        assertTrue(message("bcsdk.v1.eyJhcGkiOiJodHRwczovL2FwaS5idWd").contains("copy it again"))
    }

    @Test
    fun acceptsPaddingAndHandlesHugeVersions() {
        val hosted = list("valid").first { it["name"] == "hosted" }
        val token = hosted["token"] as String
        val padded = token + "=".repeat((4 - (token.substringAfterLast('.').length % 4)) % 4)
        val r = ConnectionToken.parse(padded) as ParsedConnectionToken.Valid
        assertEquals(hosted["ingestKey"], r.backend.ingestKey)
        val huge = ConnectionToken.parse("bcsdk.v99999999999999999999999.e30") as ParsedConnectionToken.Invalid
        assertEquals(ConnectionTokenError.UNSUPPORTED_TOKEN_VERSION, huge.error)
        // Leading zeros are still version 1.
        assertTrue(ConnectionToken.parse(token.replaceFirst("bcsdk.v1.", "bcsdk.v01.")) is ParsedConnectionToken.Valid)
    }

    @Test
    fun base64UrlDecoderMatchesTheJdk() {
        val random = java.util.Random(7)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(64)).also { random.nextBytes(it) }
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            if (encoded.isNotEmpty()) assertTrue(bytes.contentEquals(ConnectionToken.decodeBase64Url(encoded)))
        }
        assertEquals(null, ConnectionToken.decodeBase64Url("abcde"))
        assertEquals(null, ConnectionToken.decodeBase64Url("ab+c"))
    }

    @Test
    fun rejectsUrlsWithoutAHostOrWithFragments() {
        fun token(api: String) = "bcsdk.v1." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"api":"$api","key":"bct_Q2xpZW50VmlzaWJsZUluZ2VzdE9ubHlLZXlfMDEyMzQ"}""".toByteArray())
        for (bad in listOf("https:foo", "https://", "https://api.bugcapsule.com/#x", "https://a b.com", "mailto:x@y.z", "")) {
            assertTrue(bad, ConnectionToken.parse(token(bad)) is ParsedConnectionToken.Invalid)
        }
        val ok = ConnectionToken.parse(token("HTTPS://api.bugcapsule.com///")) as ParsedConnectionToken.Valid
        assertEquals("HTTPS://api.bugcapsule.com", ok.backend.url)
    }
}
