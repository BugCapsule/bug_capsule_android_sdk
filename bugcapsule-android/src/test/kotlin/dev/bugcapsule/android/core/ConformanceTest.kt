package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared conformance fixtures generated from the Dart reference (runtimes-v1 §13). */
@Suppress("UNCHECKED_CAST")
class ConformanceTest {
    private fun cases(file: String, key: String = "cases") =
        (SpecFixtures.json("conformance/$file")[key] as List<Map<String, Any?>>)

    @Test
    fun requestKeys() {
        val all = cases("request-keys.json")
        for (c in all) {
            val rules = (c["rules"] as List<Map<String, Any?>>?)?.map { r ->
                EndpointRule(
                    r["method"] as String,
                    r["path"] as String,
                    ignoreQuery = (r["ignoreQuery"] as List<String>?)?.toSet() ?: emptySet(),
                    ignoreBodyFields = (r["ignoreBodyFields"] as List<String>?)?.toSet() ?: emptySet(),
                    matchBodyFields = (r["matchBodyFields"] as List<String>?)?.toSet(),
                )
            } ?: emptyList()
            val builder = RequestKeyBuilder(
                rules = rules,
                globalIgnoreQuery = (c["globalIgnoreQuery"] as List<String>?)?.toSet()
                    ?: RequestKeyBuilder.DEFAULT_IGNORE_QUERY,
                globalIgnoreBodyFields = (c["globalIgnoreBodyFields"] as List<String>?)?.toSet() ?: emptySet(),
            )
            var uri = DartUri.parse(c["url"] as String)
            var body = c["body"]
            if (c["redact"] == true) {
                val redactor = Redactor(extraKeys = (c["redactKeys"] as List<String>?) ?: emptyList())
                uri = redactor.redactUri(uri)
                body = redactor.redactJson(body)
            }
            assertEquals(c["name"] as String, c["expectedKey"], builder.keyFor(c["method"] as String, uri, body))
        }
        assert(all.size >= 20)
    }

    @Test
    fun signatures() {
        for (c in cases("signatures.json")) {
            val kind = FailureKind.parse(c["kind"] as String)
            assertEquals(c["expected"], FailureSignature.compute(kind, c["type"] as String, c["message"] as String))
        }
    }

    @Test
    fun redaction() {
        val doc = SpecFixtures.json("conformance/redaction.json")
        val redactor = Redactor(
            extraKeys = doc["extraKeys"] as List<String>,
            extraHeaders = doc["extraHeaders"] as List<String>,
        )
        for (c in doc["keys"] as List<Map<String, Any?>>) {
            val key = c["key"] as String
            assertEquals("key $key", c["sensitive"], redactor.isSensitiveKey(key))
            assertEquals("query key $key", c["sensitiveQuery"], redactor.isSensitiveQueryKey(key))
        }
        for (c in doc["headers"] as List<Map<String, Any?>>) {
            assertEquals("header ${c["name"]}", c["sensitive"], redactor.isSensitiveHeader(c["name"] as String))
        }
        for (c in doc["scrubText"] as List<Map<String, Any?>>) {
            assertEquals(c["output"], redactor.scrubText(c["input"] as String))
        }
        for (c in doc["redactUrl"] as List<Map<String, Any?>>) {
            val out = redactor.redactUrl(c["input"] as String)
            assertEquals(c["input"] as String, c["output"], out)
            val params = DartUri.parse(out).queryParametersAll().flatMap { (k, vs) -> vs.map { listOf(k, it) } }
            assertEquals(c["params"], params)
        }
        for (c in doc["redactHeaders"] as List<Map<String, Any?>>) {
            assertEquals(c["output"], redactor.redactHeaders(c["input"] as Map<String, Any?>))
        }
        for (c in doc["redactJson"] as List<Map<String, Any?>>) {
            assertEquals(c["output"], redactor.redactJson(c["input"]))
        }
        for (c in doc["redactText"] as List<Map<String, Any?>>) {
            assertEquals(
                c["output"],
                redactor.redactText(c["id"] as String, c["value"] as String, c["sensitive"] == true),
            )
        }
    }
}
