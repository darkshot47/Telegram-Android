package com.telefarm.core.search

/**
 * Devanagari to Latin transliteration.
 *
 * Search on an Indian phone is typed in whatever script comes to hand: "कैसे हो", "kaise ho" and
 * "Kaise Ho?" all mean the same thing, and a search that only compares characters finds two of
 * the three and misses the rest.
 *
 * The direction that matters is Devanagari to Latin, because it is unambiguous: every character
 * of the script has exactly one reading. Romanized Hindi is the ambiguous one - "kaise" could be
 * written several ways - so the text of a chat is transliterated instead of the query being
 * guessed at, and both sides then share one alphabet.
 *
 * The rules are the ones a Hindi reader applies:
 *
 * * a consonant carries an inherent `a` unless a vowel sign or a virama follows it,
 * * that inherent `a` is dropped at the end of a word, which is why कैसे reads `kaise` and not
 *   `kaisea`,
 * * the anusvara and the visarga are written as `n` and `h`,
 * * the nukta forms read as the sounds they stand for, so ज़ is `z` and फ़ is `f`.
 */
object Devanagari {

    /** True when [text] holds at least one character of the script. */
    fun contains(text: String): Boolean = text.any { it.code in BLOCK_RANGE }

    /**
     * The Latin reading of [text].
     *
     * Characters outside the script are copied unchanged, so a mixed sentence keeps its English
     * words exactly as they were typed.
     */
    fun transliterate(text: String): String {
        if (!contains(text)) return text
        val source = joinNukta(text)
        val builder = StringBuilder(source.length + source.length / 2)
        var index = 0
        while (index < source.length) {
            val character = source[index]
            val consonant = CONSONANTS[character]
            if (consonant == null) {
                INDEPENDENT_VOWELS[character]?.let { builder.append(it) }
                    ?: VOWEL_SIGNS[character]?.let { builder.append(it) }
                    ?: DIGITS[character]?.let { builder.append(it) }
                    ?: builder.append(character)
                index++
                continue
            }

            builder.append(consonant)
            val next = if (index + 1 < source.length) source[index + 1] else NO_CHARACTER
            when {
                // A virama cancels the inherent vowel and is consumed with the consonant.
                next == VIRAMA -> index += 2

                VOWEL_SIGNS.containsKey(next) -> {
                    builder.append(VOWEL_SIGNS.getValue(next))
                    index += 2
                }

                // At the end of a word the inherent vowel is not pronounced.
                endsWord(next) -> index++

                else -> {
                    builder.append(INHERENT_VOWEL)
                    index++
                }
            }
        }
        return builder.toString()
    }

    /**
     * Joins a consonant and the nukta behind it into the single character for that sound.
     *
     * Text that came from a keyboard usually carries them as two characters - ज followed by a
     * combining nukta rather than the one code point for ज़ - and a stray combining mark would
     * otherwise survive into the key and stop the word from matching anything.
     */
    private fun joinNukta(text: String): String {
        if (!text.contains(NUKTA)) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val next = if (index + 1 < text.length) text[index + 1] else NO_CHARACTER
            val joined = if (next == NUKTA) NUKTA_FORMS["$character$next"] else null
            when {
                joined != null -> {
                    builder.append(joined)
                    index += 2
                }

                character == NUKTA -> index++ // a nukta on its own carries no sound of its own
                else -> {
                    builder.append(character)
                    index++
                }
            }
        }
        return builder.toString()
    }

    /** True when nothing follows, or when what follows starts a new word. */
    private fun endsWord(next: Char): Boolean =
        next == NO_CHARACTER || next.isWhitespace() || next.code !in BLOCK_RANGE

    private const val NO_CHARACTER = '\u0000'
    private const val NUKTA = '\u093C'

    /** Consonant plus combining nukta, mapped to the single character that means the same. */
    private val NUKTA_FORMS = mapOf(
        "\u0915\u093C" to "\u0958", "\u0916\u093C" to "\u0959", "\u0917\u093C" to "\u095A",
        "\u091C\u093C" to "\u095B", "\u0921\u093C" to "\u095C", "\u0922\u093C" to "\u095D",
        "\u092B\u093C" to "\u095E", "\u092F\u093C" to "\u095F"
    )
    private const val INHERENT_VOWEL = 'a'
    private const val VIRAMA = '\u094D'

    /** The Devanagari block, without the extended range that no chat title uses. */
    private val BLOCK_RANGE = 0x0900..0x097F

    private val CONSONANTS = mapOf(
        '\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "ng",
        '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j", '\u091D' to "jh", '\u091E' to "ny",
        '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n",
        '\u0924' to "t", '\u0925' to "th", '\u0926' to "d", '\u0927' to "dh", '\u0928' to "n",
        '\u0929' to "n", '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh",
        '\u092E' to "m", '\u092F' to "y", '\u0930' to "r", '\u0931' to "r", '\u0932' to "l",
        '\u0933' to "l", '\u0934' to "l", '\u0935' to "v", '\u0936' to "sh", '\u0937' to "sh",
        '\u0938' to "s", '\u0939' to "h",
        // Nukta forms: the sound they are used for, not the letter they are built from.
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z",
        '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y"
    )

    private val VOWEL_SIGNS = mapOf(
        '\u093E' to "aa", '\u093F' to "i", '\u0940' to "ii", '\u0941' to "u", '\u0942' to "uu",
        '\u0943' to "ri", '\u0944' to "rii", '\u0945' to "e", '\u0946' to "e", '\u0947' to "e",
        '\u0948' to "ai", '\u0949' to "o", '\u094A' to "o", '\u094B' to "o", '\u094C' to "au",
        '\u0900' to "n", '\u0901' to "n", '\u0902' to "n", '\u0903' to "h"
    )

    private val INDEPENDENT_VOWELS = mapOf(
        '\u0904' to "a", '\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "ii",
        '\u0909' to "u", '\u090A' to "uu", '\u090B' to "ri", '\u090C' to "rii", '\u090D' to "e",
        '\u090E' to "e", '\u090F' to "e", '\u0910' to "ai", '\u0911' to "o", '\u0912' to "o",
        '\u0913' to "o", '\u0914' to "au"
    )

    private val DIGITS = mapOf(
        '\u0966' to "0", '\u0967' to "1", '\u0968' to "2", '\u0969' to "3", '\u096A' to "4",
        '\u096B' to "5", '\u096C' to "6", '\u096D' to "7", '\u096E' to "8", '\u096F' to "9"
    )
}
