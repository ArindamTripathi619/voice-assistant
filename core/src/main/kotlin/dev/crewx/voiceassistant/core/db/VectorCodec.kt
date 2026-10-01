package dev.crewx.voiceassistant.core.db

/**
 * Quantizes embedding vectors to int8 and computes cosine similarity.
 *
 * Stored as a BLOB so `embeddings` stays small: a 384-dim MiniLM vector is 384
 * bytes rather than 1536. Quantization is per-vector scale = max|component|,
 * which bounds absolute error at ~1/127 of full scale; that is well inside the
 * tolerance of a *ranking* fallback tier, and it is the last tier anyway.
 *
 * Big-endian ByteBuffer is used deliberately so a blob written on one device is
 * readable on another with a different native byte order.
 */
object VectorCodec {

    /** SQLite has no vector type, so the stored length implies the dimension. */
    const val MAX_DIM = 4096

    fun encode(vector: FloatArray): ByteArray {
        require(vector.isNotEmpty()) { "refusing to encode an empty vector" }
        require(vector.size <= MAX_DIM) { "dimension ${vector.size} exceeds $MAX_DIM" }
        val scale = vector.maxOf { kotlin.math.abs(it) }
        if (scale == 0f) return ByteArray(vector.size)
        val out = ByteArray(vector.size)
        for (i in vector.indices) {
            val normalized = vector[i] / scale
            // Clamp before narrowing: 1.0000001f would otherwise wrap to -128.
            val q = (normalized * 127f).toInt().coerceIn(-127, 127)
            out[i] = q.toByte()
        }
        return out
    }

    /**
     * Dequantizes back to a *unit-max* vector.
     *
     * The original per-vector scale is deliberately not recoverable, so this
     * returns values in roughly [-1, 1] rather than the original magnitudes.
     * That is sufficient because every consumer scores with cosine similarity,
     * which is scale-invariant - but it does mean a decoded vector must never be
     * used where absolute magnitude matters.
     */
    fun decode(blob: ByteArray): FloatArray =
        FloatArray(blob.size) { blob[it] / 127f }

    /**
     * Cosine similarity, or 0 when either side has no magnitude.
     *
     * Returns 0 rather than NaN for a zero vector so a corrupt row degrades to
     * "no match" instead of poisoning the sort comparator.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        if (magA == 0.0 || magB == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(magA) * kotlin.math.sqrt(magB))).toFloat()
    }
}
