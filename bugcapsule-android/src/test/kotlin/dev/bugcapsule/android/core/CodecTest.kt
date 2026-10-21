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
        val files = SpecFixtures.files("capsule-v1/valid")
        assertTrue(files.size >= 7)
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
        }
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
        val files = SpecFixtures.files("capsule-v1/invalid")
        assertTrue(files.size >= 30)
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
        doc["specVersion"] = 2L
        expectFormatError(gz(Json.stringify(doc)), "\$.specVersion 2 is newer")
    }

    @Test
    fun integersStayIntegers() {
        val doc = SpecFixtures.json("capsule-v1/valid/minimal.json")["document"] as Map<String, Any?>
        val text = String(java.util.zip.GZIPInputStream(CapsuleCodec.encode(CapsuleCodec.fromJson(doc)).inputStream()).readBytes())
        assertTrue(text.contains("\"specVersion\":1,"))
        assertTrue(!text.contains("\"seq\":1.0"))
    }
}
