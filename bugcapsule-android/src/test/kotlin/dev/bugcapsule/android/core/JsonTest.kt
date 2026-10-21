package dev.bugcapsule.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun numbersFollowDart() {
        assertEquals(1L, Json.parse("1"))
        assertEquals(1.0, Json.parse("1.0"))
        assertEquals(1000.0, Json.parse("1e3"))
        assertTrue(Json.parse("123456789012345678901234567890") is Double)
        assertEquals("1.5", Json.formatDouble(1.5))
        assertEquals("1.0", Json.formatDouble(1.0))
        assertEquals("-0.0", Json.formatDouble(-0.0))
        assertEquals("1e+21", Json.formatDouble(1e21))
        assertEquals("123456789012345680000.0", Json.formatDouble(123456789012345680000.0))
        assertEquals("0.000001", Json.formatDouble(0.000001))
        assertEquals("1e-7", Json.formatDouble(1e-7))
        assertEquals("1.5e-7", Json.formatDouble(1.5e-7))
        assertEquals("0.1", Json.formatDouble(0.1))
        assertEquals("411.42857142857144", Json.formatDouble(2880.0 / 7.0))
    }

    @Test
    fun stringsEscapeLikeDart() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001é中\"", Json.stringify("a\"b\\c\n\u0001é中"))
        assertEquals("{\"b\":2,\"a\":[true,null]}", Json.stringify(linkedMapOf("b" to 2, "a" to listOf(true, null))))
        assertEquals("{\"a\":[true,null],\"b\":2}", Json.canonical(linkedMapOf("b" to 2, "a" to listOf(true, null))))
    }

    @Test
    fun roundTrip() {
        val text = "{\"x\":[1,2.5,\"s\",{\"y\":null}],\"u\":\"\\u00e9\\ud83d\\ude00\"}"
        assertEquals("{\"x\":[1,2.5,\"s\",{\"y\":null}],\"u\":\"é😀\"}", Json.stringify(Json.parse(text)))
    }

    @Test(expected = JsonFormatException::class)
    fun rejectsTrailingGarbage() {
        Json.parse("{} x")
    }

    @Test(expected = JsonFormatException::class)
    fun rejectsControlCharacters() {
        Json.parse("\"a\u0001\"")
    }
}
