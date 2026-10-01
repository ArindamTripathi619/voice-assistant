package dev.crewx.voiceassistant.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextNormalizerTest {

    @Test
    fun `lowercases and strips punctuation`() {
        assertEquals("rohan das", TextNormalizer.normalize("Rohan Das"))
        assertEquals("rohan das", TextNormalizer.normalize("Rohan, Das."))
        assertEquals("a b", TextNormalizer.normalize("  a   b  "))
    }

    @Test
    fun `strips diacritics`() {
        assertEquals("jose", TextNormalizer.normalize("José"))
        assertEquals("muller", TextNormalizer.normalize("Müller"))
        assertEquals("nunez", TextNormalizer.normalize("Nuñez"))
    }

    @Test
    fun `maps native script digits to ascii`() {
        assertEquals("40", TextNormalizer.normalize("४०"))
        assertEquals("30", TextNormalizer.normalize("৩০"))
        assertEquals("70", TextNormalizer.normalize("٧٠"))
        assertEquals("brightness 60", TextNormalizer.normalize("brightness ६०"))
    }

    @Test
    fun `entity form removes filler words`() {
        assertEquals("wife", TextNormalizer.normalizeForEntity("my wife"))
        assertEquals("rohan", TextNormalizer.normalizeForEntity("can you please call rohan"))
    }

    @Test
    fun `entity form never returns blank for filler only`() {
        assertEquals("", TextNormalizer.normalizeForEntity("please can you"))
        assertTrue(TextNormalizer.normalizeForEntity("please").isEmpty())
    }

    @Test
    fun `strips leading verbs`() {
        assertEquals("rohan", TextNormalizer.normalizeAfterVerb("call rohan"))
        assertEquals("wife", TextNormalizer.normalizeAfterVerb("text my wife"))
        assertEquals("wife", TextNormalizer.normalizeAfterVerb("message my wife"))
        // "call" is the entity itself if it leads with no object
        assertEquals("", TextNormalizer.normalizeAfterVerb("call"))
    }

    @Test
    fun `keeps non-filler words that could be names`() {
        // "call" is in LEAD_VERBS, but a contact actually named "Call" must survive
        // once the verb has already been consumed.
        assertEquals("callaway", TextNormalizer.normalizeAfterVerb("text callaway"))
    }

    @Test
    fun `sameEntity compares normalized forms`() {
        assertTrue(TextNormalizer.sameEntity("Rohan Das", "rohan das"))
        assertTrue(TextNormalizer.sameEntity("José", "jose"))
        assertTrue(!TextNormalizer.sameEntity("rohan", "priya"))
        assertTrue(!TextNormalizer.sameEntity("", ""))
    }

    @Test
    fun `tokenize returns normalized tokens without fillers`() {
        assertEquals(listOf("turn", "flashlight"), TextNormalizer.tokenize("please turn on the flashlight"))
    }
}
