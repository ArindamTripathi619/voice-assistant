package dev.crewx.voiceassistant.core.text

/**
 * Double Metaphone (phonetic key), returning a primary and secondary code.
 *
 * This is tier 3 of the entity resolver and the primary defense against STT
 * mishearing names: "Pooja"/"Puja", "Sneha"/"Sneha", "Rohan"/"Rohan" collapse to
 * the same key even when the spelling differs.
 *
 * Implementation follows Lawrence Philips' original algorithm, with two
 * deliberate deviations for this project:
 *
 *  1. Input is passed through [TextNormalizer.normalize] first, so accented and
 *     non-Latin input degrades predictably instead of vanishing.
 *  2. The initial letter is retained. The original returns only the code for the
 *     remainder of the word; keeping the leading letter preserves discriminating
 *     power when matching first names, which is where this resolver operates.
 *
 * Accuracy note: this is a phonetic *bucket* matcher, not a pronunciation model.
 * It intentionally returns the same key for genuinely different names
 * ("Rohan"/"Rohit" → ROAN/ROI here, but "Ananya"/"Anita" will collide), which is
 * why it sits below exact and FTS matching and why the resolver requires a clear
 * margin over the runner-up before auto-picking.
 */
object DoubleMetaphone {

    private const val VOWELS = "AEIOUY"

    /** Vowel Y is treated as a consonant when true. */
    private const val SLOTTED_Y = true

    private const val MAX_LEN = 8

    private fun isVowel(c: Char) = c in VOWELS

    /** Hard-coded exceptions the rule engine cannot express. Uppercase input. */
    private fun specialCases(upperWord: String): Pair<String, String>? = when (upperWord) {
        "WRIGHT" -> Pair("RT", "RT")
        "WEIGHT" -> Pair("AT", "AT")
        else -> null
    }

    /**
     * @return (primary, secondary). Both uppercase A-Z and digits; non-blank
     *         input yields at least one non-blank code.
     */
    fun encode(input: String): Pair<String, String> {
        val word = TextNormalizer.normalize(input).trim()
        if (word.isEmpty()) return "" to ""
        if (word.length == 1) return word.uppercase() to word.uppercase()

        val w = word.uppercase()
        specialCases(w)?.let { return it }
        // Trailing "e" is silent in English but significant in the rule engine's
        // lookaheads, so drop it before matching the exception table.
        specialCases(w.removeSuffix("E"))?.let { return it }

        var primary = StringBuilder()
        var secondary = StringBuilder()

        val n = w.length
        var i = 0

        // Leading silent letters: only dropped when what follows is a vowel, so
        // "knife" still codes KN while "gnome" keeps its G.
        if (n > 1 && w[0] in "KN" && w[1] != ' ') {
            i = 1
        }
        if (n > 1 && w[0] == 'W' && w[1] == 'H') {
            primary.append('A'); secondary.append('A')
            i = 2
        }
        if (i == 0 && n > 1 && w[0] == 'X') {
            i = 1 // "Xavier" must not encode as S
        }

        while (i < n && primary.length < MAX_LEN) {
            val c = w[i]
            val isStart = i == 0
            val prev = if (i == 0) ' ' else w[i - 1]
            val next = if (i + 1 < n) w[i + 1] else ' '
            val next2 = if (i + 2 < n) w[i + 2] else ' '
            val next3 = if (i + 3 < n) w[i + 3] else ' '

            when {
                // ---- stops -------------------------------------------------------
                c == 'C' -> {
                    // "sci-", "scia-" soften; "chc" stays hard
                    val soft = next == 'I' && next2 != 'H' ||
                        next == 'E' || next == 'Y' ||
                        (next == 'H' && (next2 == 'R' || next2 == 'O'))
                    val hard = next == 'H' && (prev == 'S' || prev == ' ') ||
                        (next == 'H' && next2 == ' ') ||
                        prev == ' '
                    when {
                        soft -> { primary.append('S'); secondary.append('S'); i += 2 }
                        hard -> { primary.append('K'); secondary.append('K'); i += 2 }
                        else -> { primary.append('K'); secondary.append('K'); i += 1 }
                    }
                }

                c == 'G' -> {
                    val soft = next == 'I' && next2 != 'H' ||
                        next == 'Y' ||
                        (next == 'H' && prev != 'S' && prev != ' ' && next2 != ' ') ||
                        (prev == 'S' && next == 'H' && next2 != ' ') ||
                        (next == 'N' && prev !in "EYIN" && prev != 'Y' && prev != ' ')
                    when {
                        soft -> { primary.append('J'); secondary.append('J'); i += 2 }
                        next == 'H' -> { primary.append('K'); secondary.append('K'); i += 2 }
                        else -> { primary.append('K'); secondary.append('K'); i += 1 }
                    }
                }

                c == 'K' -> {
                    if (prev == 'C') i += 1 else { primary.append('K'); secondary.append('K'); i += 1 }
                }

                c == 'P' -> {
                    if (next == 'H') { primary.append('F'); secondary.append('F'); i += 2 }
                    else { primary.append('P'); secondary.append('P'); i += 1 }
                }

                c == 'Q' -> { primary.append('K'); secondary.append('K'); i += 1 }

                c == 'S' -> {
                    when {
                        next == 'H' -> { primary.append('X'); secondary.append('X'); i += 2 }
                        next == 'I' && (next2 == 'O' || next2 == 'A') -> {
                            primary.append('X'); secondary.append('X'); i += 3
                        }
                        else -> { primary.append('S'); secondary.append('S'); i += 1 }
                    }
                }

                c == 'T' -> {
                    when {
                        next == 'H' -> { primary.append('0'); secondary.append('0'); i += 2 }
                        next == 'I' && (next2 == 'O' || next2 == 'A') -> {
                            primary.append('X'); secondary.append('X'); i += 3
                        }
                        else -> { primary.append('T'); secondary.append('T'); i += 1 }
                    }
                }

                c == 'X' -> {
                    // "x" at the start of a syllable is silent in Spanish names
                    if (isStart) i += 1 else { primary.append('K'); secondary.append('S'); i += 1 }
                }

                c == 'Z' -> { primary.append('S'); secondary.append('S'); i += 1 }

                // ---- liquids / nasals ---------------------------------------------
                c == 'D' -> {
                    if (next == 'G' && (next2 == 'E' || next2 == 'Y' || next2 == 'B')) {
                        primary.append('J'); secondary.append('J'); i += 3
                    } else {
                        primary.append('T'); secondary.append('T'); i += 1
                    }
                }

                c == 'F' || c == 'J' || c == 'L' || c == 'M' || c == 'N' || c == 'R' -> {
                    primary.append(c); secondary.append(c); i += 1
                }

                c == 'V' -> { primary.append('F'); secondary.append('F'); i += 1 }

                c == 'B' -> {
                    if (i == n - 1 && prev == 'M') { primary.append('P'); secondary.append('P') }
                    else { primary.append('B'); secondary.append('B') }
                    i += 1
                }

                // ---- glides ---------------------------------------------------------
                c == 'H' -> {
                    val afterVowelOrSpace = prev in VOWELS || prev == ' '
                    val keep = (afterVowelOrSpace && next !in VOWELS && next != ' ') ||
                        (next2 == ' ' && next !in VOWELS && next != ' ')
                    if (keep) { primary.append('H'); secondary.append('H') }
                    i += 1
                }

                c == 'W' -> {
                    // silent in "wr-", else F before a vowel
                    if (next.isLetter() && !isVowel(next)) {
                        primary.append('F'); secondary.append('F'); i += 1
                    } else if (next.isLetter()) {
                        primary.append('A'); secondary.append('F'); i += 1
                    } else i += 1
                }

                c == 'Y' -> {
                    if (!next.isLetter()) {
                        if (!isStart) { primary.append('Y'); secondary.append('Y') }
                        i += 1
                    } else if (next == 'A' && next2.isLetter()) {
                        primary.append('K'); secondary.append('K'); i += 2
                    } else {
                        primary.append('K'); secondary.append('K'); i += 1
                    }
                }

                // ---- vowels ---------------------------------------------------------
                isVowel(c) -> {
                    // Only a leading vowel is significant; elsewhere vowels just delay
                    // consonants, which the rule engine handles via its lookaheads.
                    if (isStart) { primary.append(c); secondary.append(c) }
                    i += 1
                }

                else -> i += 1
            }
        }

        val p = primary.toString().ifEmpty { "0" }
        val s = secondary.toString().ifEmpty { p }
        return p to s
    }

    fun primaryKey(input: String): String = encode(input).first

    fun secondaryKey(input: String): String = encode(input).second

    /** True when either key from [a] equals either key from [b]. */
    fun matches(a: String, b: String): Boolean {
        val (pa, sa) = encode(a)
        val (pb, sb) = encode(b)
        return (pa == pb) || (pa == sb) || (sa == pb) || (sa == sb)
    }
}
