package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class CodecTest {
    private fun gz(text: String) = CapsuleCodec.gzip(text.toByteArray())

    private fun expectFormatError(bytes: ByteArray, contains: String) {
        try {
            CapsuleCodec.decode(bytes)
            fail("expected CapsuleFormatException containing \"$contains\"")
        } catch (e: CapsuleFormatException) {
            assertTrue("\"${e.message}\" should contain \"$contains\"", e.message!!.contains(contains))
        }
    }

    @Test
    fun everyValidFixtureDecodesAndReEncodesValid() {
        assertTrue(SpecFixtures.files("capsule-v1/valid").size >= 7)
        assertTrue(SpecFixtures.files("capsule-v2/valid").size >= 5)
        val files = SpecFixtures.files("capsule-v1/valid") + SpecFixtures.files("capsule-v2/valid")
        for (f in files) {
            val doc = (Json.parse(f.readText()) as Map<String, Any?>)["document"] as Map<String, Any?>
            val capsule = CapsuleCodec.decode(gz(Json.stringify(doc)))
            val reencoded = CapsuleCodec.encode(capsule)
            val again = CapsuleCodec.decode(reencoded)
            assertNull(f.name, CapsuleValidator.validate(CapsuleCodec.toJson(again)))
            assertEquals(f.name, capsule.manifest.capsuleId, again.manifest.capsuleId)
            assertEquals(f.name, capsule.events.size, again.events.size)
            assertEquals(f.name, capsule.events, again.events)
            assertEquals(f.name, capsule.manifest.failure, again.manifest.failure)
            assertEquals(f.name, capsule.manifest.dependencies, again.manifest.dependencies)
            assertEquals(f.name, doc["specVersion"], CapsuleCodec.toJson(again)["specVersion"].let { (it as Int).toLong() })
        }
    }

    @Test
    fun v2DependenciesAreRead() {
        val doc = SpecFixtures.json("capsule-v2/valid/websocket.json")["document"] as Map<String, Any?>
        val ws = CapsuleCodec.fromJson(doc).dependency("ws")!!
        assertEquals("bug_capsule.web_socket_channel", ws.adapter)
        assertEquals(1, ws.schema)
        assertTrue(ws.required)
        assertEquals(DependencyCoverage.COMPLETE, ws.coverage)
        assertEquals(mapOf("web_socket_channel" to "3.0.3"), ws.tested)
        assertEquals(9L, ws.stats["events"])
        val native = CapsuleCodec.fromJson(SpecFixtures.json("capsule-v2/valid/native-adapters.json")["document"] as Map<String, Any?>)
        assertEquals(DependencyCoverage.DIAGNOSTIC, native.dependency("analytics")!!.coverage)
    }

    @Test
    fun writerEmitsV2OnlyWithDependencies() {
        val minimal = SpecFixtures.json("capsule-v1/valid/minimal.json")["document"] as Map<String, Any?>
        val v1 = CapsuleCodec.fromJson(minimal)
        assertEquals(1, CapsuleCodec.toJson(v1)["specVersion"])
        assertTrue(!CapsuleCodec.toJson(v1).let { (it["manifest"] as Map<*, *>).containsKey("dependencies") })
        val dep = CapsuleDependency("ws", DependencyKinds.WEBSOCKET, "bugcapsule-okhttp.websocket", "0.5.0", 1, true, DependencyCoverage.COMPLETE)
        val v2 = v1.copy(manifest = v1.manifest.copy(dependencies = listOf(dep)))
        val json = CapsuleCodec.toJson(v2)
        assertEquals(2, json["specVersion"])
        assertNull(CapsuleValidator.validate(Json.parse(Json.stringify(json))))
        assertEquals(listOf(dep), CapsuleCodec.decode(CapsuleCodec.encode(v2)).manifest.dependencies)
    }

    @Test
    fun aV1DocumentIgnoresDependenciesAndInteractionEvents() {
        val doc = (SpecFixtures.json("capsule-v1/valid/minimal.json")["document"] as Map<String, Any?>).toMutableMap()
        val manifest = (doc["manifest"] as Map<String, Any?>).toMutableMap()
        manifest["dependencies"] = "not validated in v1"
        doc["manifest"] = manifest
        doc["events"] = listOf(mapOf("seq" to 1L, "t" to 0L, "type" to "ws.message", "data" to mapOf("conn" to 3L)))
        val capsule = CapsuleCodec.decode(gz(Json.stringify(doc)))
        assertTrue(capsule.manifest.dependencies.isEmpty())
        assertEquals(1, capsule.specVersion)
    }

    @Test
    fun runtimeMetadataIsRead() {
        val doc = SpecFixtures.json("capsule-v1/valid/android-runtime.json")["document"] as Map<String, Any?>
        val capsule = CapsuleCodec.fromJson(doc)
        assertEquals("android", capsule.manifest.runtimeName)
        assertTrue(capsule.manifest.capabilities.isNotEmpty())
        val legacy = SpecFixtures.json("capsule-v1/valid/full.json")["document"] as Map<String, Any?>
        assertEquals("flutter", CapsuleCodec.fromJson(legacy).manifest.runtimeName)
    }

    @Test
    fun everyInvalidFixtureIsRejectedWithItsPath() {
        assertTrue(SpecFixtures.files("capsule-v1/invalid").size >= 30)
        assertTrue(SpecFixtures.files("capsule-v2/invalid").size >= 25)
        val files = SpecFixtures.files("capsule-v1/invalid") + SpecFixtures.files("capsule-v2/invalid")
        for (f in files) {
            val fixture = Json.parse(f.readText()) as Map<String, Any?>
            val path = fixture["errorPath"] as String
            try {
                CapsuleCodec.decode(gz(Json.stringify(fixture["document"])))
                fail("${f.name} was accepted")
            } catch (e: CapsuleFormatException) {
                val msg = e.message!!.removePrefix("malformed capsule: ")
                assertTrue("${f.name}: \"$msg\" should start with $path", msg.startsWith("$path ") || msg == path)
            }
        }
    }

    @Test
    fun rejectsNonGzip() = expectFormatError("{}".toByteArray(), "not a gzip file")

    @Test
    fun rejectsTruncatedGzip() {
        val bytes = gz("{\"format\":\"bugcapsule.capsule\"}")
        expectFormatError(bytes.copyOf(bytes.size - 6), "gzip")
    }

    @Test
    fun gzipBombIsStoppedWhileInflating() {
        // 50 MB of spaces compress to ~50 KB.
        val bomb = CapsuleCodec.gzip(ByteArray(50 * 1024 * 1024) { ' '.code.toByte() })
        assertTrue(bomb.size < 200 * 1024)
        try {
            CapsuleCodec.decode(bomb, maxDecompressedBytes = 1024 * 1024)
            fail("bomb accepted")
        } catch (e: CapsuleFormatException) {
            assertTrue(e.message!!.contains("exceeds 1048576 bytes"))
        }
    }

    @Test
    fun jsonValueCountIsCheckedBeforeParsing() {
        val many = StringBuilder("[")
        repeat(CapsuleLimits.MAX_JSON_VALUES + 5) { if (it > 0) many.append(','); many.append('0') }
        many.append(']')
        expectFormatError(gz(many.toString()), "more than 4000000 JSON values")
    }

    @Test
    fun countJsonValuesIgnoresStrings() {
        assertEquals(1, CapsuleCodec.countJsonValues("\"a,b,[{\\\"\"".toByteArray(), 100))
        assertEquals(5, CapsuleCodec.countJsonValues("[1,2,{}]".toByteArray(), 100))
    }

    @Test
    fun depthBeyondTheParserLimitIsAFormatError() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        expectFormatError(gz(deep), "nests deeper than 100 levels")
    }

    @Test
    fun newerSpecVersionIsUnsupported() {
        val doc = (SpecFixtures.json("capsule-v1/valid/minimal.json")["document"] as Map<String, Any?>).toMutableMap()
        doc["specVersion"] = 3L
        expectFormatError(gz(Json.stringify(doc)), "\$.specVersion 3 is newer than this reader (v2)")
    }

    @Test
    fun integersStayIntegers() {
        val doc = SpecFixtures.json("capsule-v1/valid/minimal.json")["document"] as Map<String, Any?>
        val text = String(java.util.zip.GZIPInputStream(CapsuleCodec.encode(CapsuleCodec.fromJson(doc)).inputStream()).readBytes())
        assertTrue(text.contains("\"specVersion\":1,"))
        assertTrue(!text.contains("\"seq\":1.0"))
    }
}
