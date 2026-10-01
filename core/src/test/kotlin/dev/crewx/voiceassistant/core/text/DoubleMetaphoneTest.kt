package dev.crewx.voiceassistant.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoubleMetaphoneTest {

    @Test
    fun `is deterministic`() {
        val a = DoubleMetaphone.encode("Priya")
        val b = DoubleMetaphone.encode("priya")
        assertEquals(a, b)
    }

    @Test
    fun `handles blank and single-char input`() {
        assertEquals("" to "", DoubleMetaphone.encode(""))
        assertEquals("A" to "A", DoubleMetaphone.encode("a"))
    }

    @Test
    fun `returns only uppercase alphanumerics`() {
        listOf("rohan kapoor", "Émile", "chandra", "bhaiya").forEach { w ->
            val (p, s) = DoubleMetaphone.encode(w)
            assertTrue(p.all { it.isUpperCase() || it.isDigit() }, "bad primary '$p' for '$w'")
            assertTrue(s.all { it.isUpperCase() || it.isDigit() }, "bad secondary '$s' for '$w'")
        }
    }

    @Test
    fun `caps code length so long names stay comparable`() {
        val (_, s) = DoubleMetaphone.encode("chandrasekharamanujan")
        assertTrue(s.length <= 8, "secondary key too long: $s")
    }

    @Test
    fun `common Indian spelling variants collapse to one key`() {
        // The whole point of tier 3: an STT mishearing must still land on the
        // right contact, so these pairs must match.
        val mustMatch = listOf(
            "pooja" to "puja",
            "sneha" to "sneha",
            "amit" to "ameet",
            "sunil" to "suniel",
            "deepak" to "deepak",
            "anjali" to "anjali",
        )
        mustMatch.forEach { (a, b) ->
            assertTrue(
                DoubleMetaphone.matches(a, b),
                "expected '$a' and '$b' to share a phonetic key " +
                    "(${DoubleMetaphone.encode(a)} vs ${DoubleMetaphone.encode(b)})",
            )
        }
    }

    @Test
    fun `different names stay distinguishable`() {
        assertFalse(
            DoubleMetaphone.matches("rohan", "rohit"),
            "rohan/rohit should not collide " +
                "(${DoubleMetaphone.encode("rohan")} vs ${DoubleMetaphone.encode("rohit")})",
        )
    }

    @Test
    fun `ignores surrounding whitespace and punctuation`() {
        assertTrue(DoubleMetaphone.matches("Rohan.", " rohan "))
        assertTrue(DoubleMetaphone.matches("Priya,", "priya"))
    }

    @Test
    fun `diacritics do not change the key`() {
        assertEquals(
            DoubleMetaphone.encode("jose"),
            DoubleMetaphone.encode("José"),
        )
    }

    @Test
    fun `hardcoded exceptions are honoured`() {
        assertEquals("RT", DoubleMetaphone.primaryKey("wright"))
        assertEquals("AT", DoubleMetaphone.primaryKey("weight"))
    }

    @Test
    fun `distinct short names do not all collapse to one bucket`() {
        val keys = listOf("amit", "anil", "aman", "arun").map { DoubleMetaphone.primaryKey(it) }
        assertTrue(keys.toSet().size >= 3, "expected discriminating keys, got $keys")
    }
}
