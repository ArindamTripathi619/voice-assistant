package dev.crewx.voiceassistant.core.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class VectorCodecTest {

    @Test
    fun `round trip preserves direction within quantization error`() {
        val v = floatArrayOf(1f, 2f, 3f, 4f)
        val decoded = VectorCodec.decode(VectorCodec.encode(v))
        assertEquals(v.size, decoded.size)
        // Magnitude is not preserved (see decode's contract); direction is what
        // cosine uses, and it must survive to within the int8 step.
        decoded.forEachIndexed { i, actual ->
            val expected = v[i] / v.max()
            assertTrue(
                abs(actual - expected) < 0.01f,
                "component $i drifted: expected $expected, got $actual",
            )
        }
    }

    @Test
    fun `a decoded vector preserves cosine similarity to the original`() {
        // The property the resolver actually depends on, checked end to end so a
        // broken codec cannot slip through on magnitude-invariant scores alone.
        val original = floatArrayOf(0.2f, -0.9f, 0.55f, 0.1f)
        val decoded = VectorCodec.decode(VectorCodec.encode(original))
        assertEquals(1.0f, VectorCodec.cosine(original, decoded), 1e-3f)
    }

    @Test
    fun `encode normalizes by the max component so magnitude is dropped`() {
        val small = VectorCodec.decode(VectorCodec.encode(floatArrayOf(0.001f, 0.002f)))
        val large = VectorCodec.decode(VectorCodec.encode(floatArrayOf(100f, 200f)))
        // Ratios survive to within one int8 step. The small vector quantizes
        // 0.5 -> 63/127, so a component ratio is accurate to ~1/127, not 1e-3;
        // that is the documented cost of the int8 fallback tier.
        val ratio = 0.5f / 0.25f
        assertEquals(ratio, abs(small[1]) / abs(small[0]), 0.02f)
        assertEquals(ratio, abs(large[1]) / abs(large[0]), 0.02f)
        assertEquals(abs(small[1]), abs(large[1]), 0.01f)
    }

    @Test
    fun `negative components survive without wrapping`() {
        val decoded = VectorCodec.decode(VectorCodec.encode(floatArrayOf(-5f, 5f)))
        assertTrue(decoded[0] < 0f, "negative must stay negative, got ${decoded[0]}")
        assertTrue(decoded[1] > 0f)
    }

    @Test
    fun `the extreme positive value does not wrap to the negative minimum`() {
        // 1.0f * 127 must clamp, not overflow into -128.
        val decoded = VectorCodec.decode(VectorCodec.encode(floatArrayOf(1f, 0.0001f)))
        assertTrue(decoded[0] > 0.99f, "got ${decoded[0]}")
    }

    @Test
    fun `cosine of identical vectors is one`() {
        val v = floatArrayOf(0.3f, -0.7f, 0.5f)
        assertEquals(1.0f, VectorCodec.cosine(v, v), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertEquals(0.0f, VectorCodec.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `cosine ignores magnitude`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        assertEquals(1.0f, VectorCodec.cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine of opposite vectors is minus one`() {
        assertEquals(-1.0f, VectorCodec.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-5f)
    }

    @Test
    fun `zero vectors give zero rather than NaN`() {
        // A corrupt row must degrade to "no match" instead of poisoning a sort.
        val zero = floatArrayOf(0f, 0f, 0f)
        assertEquals(0f, VectorCodec.cosine(zero, floatArrayOf(1f, 2f)))
        assertEquals(0f, VectorCodec.cosine(floatArrayOf(1f, 2f), zero))
        assertEquals(0f, VectorCodec.cosine(zero, zero))
    }

    @Test
    fun `mismatched dimensions give zero rather than throwing`() {
        assertEquals(0f, VectorCodec.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun `an all-zero vector encodes to all zeros without dividing by zero`() {
        val blob = VectorCodec.encode(floatArrayOf(0f, 0f))
        assertTrue(blob.all { it == 0.toByte() })
    }

    @Test
    fun `encode rejects unusable input`() {
        val empty = runCatching { VectorCodec.encode(FloatArray(0)) }
        assertTrue(empty.isFailure, "empty vector must be rejected, not stored")
        val huge = runCatching { VectorCodec.encode(FloatArray(VectorCodec.MAX_DIM + 1) { 1f }) }
        assertTrue(huge.isFailure)
    }

    @Test
    fun `blob layout is independent of platform byte order`() {
        // A blob written on one device must be readable on another; big-endian
        // packing is what guarantees that, and it is asserted so a future
        // "optimization" to native order has to be a deliberate change.
        val blob = VectorCodec.encode(floatArrayOf(1f, 0f))
        assertEquals(2, blob.size)
        assertEquals(127.toByte(), blob[0])
        assertEquals(0.toByte(), blob[1])
    }

    @Test
    fun `decode of an empty blob is an empty vector`() {
        assertEquals(0, VectorCodec.decode(ByteArray(0)).size)
    }
}
