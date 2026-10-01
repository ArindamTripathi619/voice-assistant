package dev.crewx.voiceassistant.core.text

import java.text.Normalizer as JavaNormalizer

/**
 * Single normalization used by every matcher in the system: alias_norm lookups,
 * FTS queries, fast-path slot extraction and app/routine matching.
 *
 * Rules (order matters):
 *  1. Unicode NFKD + strip combining marks -> "Jose" from "José"
 *  2. lowercase
 *  3. map non-ASCII digits (Devanagari, Bengali, Arabic-Indic) to ASCII 0-9,
 *     because STT output for "40 percent" frequently arrives in native digits
 *  4. replace every non-alphanumeric run with a single space
 *  5. drop filler/command words that carry no entity signal
 *  6. collapse whitespace, trim
 *
 * Keeping this in one place is what stops "Rohan " / "roh-an" / "रोहन" from
 * diverging between the alias table and the query path.
 */
object TextNormalizer {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Digits in scripts STT commonly emits, mapped to ASCII.
     * Ranges verified: Devanagari U+0966, Bengali U+09E6, Gujarati U+0AE6,
     * Oriya U+0B66, Tamil U+0BE6, Telugu U+0C66, Kannada U+0CE6,
     * Malayalam U+0D66, Thai U+0E50, Arabic-Indic U+0660.
     */
    private val DIGIT_MAP: Map<Char, Char> = buildMap {
        fun addRange(start: Int, end: Int) {
            for (cp in start..end) put(cp.toChar(), ('0' + (cp - start)))
        }
        addRange(0x0966, 0x096F) // Devanagari
        addRange(0x09E6, 0x09EF) // Bengali
        addRange(0x0AE6, 0x0AEF) // Gujarati
        addRange(0x0B66, 0x0B6F) // Oriya
        addRange(0x0BE6, 0x0BEF) // Tamil
        addRange(0x0C66, 0x0C6F) // Telugu
        addRange(0x0CE6, 0x0CEF) // Kannada
        addRange(0x0D66, 0x0D6F) // Malayalam
        addRange(0x0E50, 0x0E59) // Thai
        addRange(0x0660, 0x0669) // Arabic-Indic
    }

    /**
     * Words that carry no entity signal. Deliberately conservative: only
     * pure filler is removed, never anything that could be a name.
     */
    val STOP_WORDS: Set<String> = setOf(
        "please", "pls", "can", "could", "would", "you", "u",
        "the", "a", "an", "my", "me", "i", "of", "for", "to", "is", "am",
        "hey", "ok", "okay", "just", "now", "then", "also", "um", "uh",
        "and", "but", "so", "lets", "let", "us", "do", "does", "did",
        "kindly", "quickly", "right", "yeah", "yes", "no", "not",
        // Safe to drop for entity lookup: they carry no name signal, and
        // leaving them in would corrupt FTS matching ("turn flashlight" must not
        // become the token "on").
        "on", "off", "up", "down", "make", "get", "give", "set", "put",
    )

    /** Verbs used to route person references; stripped before alias matching. */
    val LEAD_VERBS: Set<String> = setOf(
        "call", "phone", "ring", "text", "message", "send", "whatsapp",
        "telegram", "chat", "ask", "tell", "notify", "ping",
        "play", "put", "start",
        "open", "launch", "run", "go",
    )

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        var s = JavaNormalizer.normalize(input, JavaNormalizer.Form.NFKD)
        s = COMBINING_MARKS.replace(s, "")
        s = s.lowercase()
        s = s.map { ch -> DIGIT_MAP[ch] ?: ch }.joinToString("")
        s = NON_ALNUM.replace(s, " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Normalize, then remove filler words and command verbs. Use for entity
     * lookups: "can you please call rohan" -> "rohan".
     *
     * Verbs are stripped anywhere in the string, not just leading, because
     * transcripts routinely interleave them ("text my wife that i will be late").
     */
    fun normalizeForEntity(input: String): String {
        val n = normalize(input)
        if (n.isEmpty()) return ""
        return n.split(' ')
            .filter { it.isNotEmpty() && it !in STOP_WORDS && it !in LEAD_VERBS }
            .joinToString(" ")
    }

    /**
     * Position-preserving variant of [normalizeForEntity] that also drops the
     * trailing copula of a relation phrase ("my wife is priya" -> "wife priya").
     */
    fun normalizeAfterVerb(input: String): String = normalizeForEntity(input)

    /** Word tokens, normalized, fillers removed. */
    fun tokenize(input: String): List<String> =
        normalizeForEntity(input).split(' ').filter { it.isNotEmpty() }

    /** True if [a] and [b] match after normalization. */
    fun sameEntity(a: String, b: String): Boolean =
        normalizeForEntity(a) == normalizeForEntity(b) && normalizeForEntity(a).isNotEmpty()
}
