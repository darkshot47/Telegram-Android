package com.telefarm.core.search

/**
 * The one form of a text that search compares.
 *
 * Two strings mean the same thing to a person when their keys are equal, whatever script, case
 * or punctuation they were typed in:
 *
 * * the case is folded,
 * * accents are dropped, so `José` and `Jose` are one name,
 * * Devanagari is transliterated, so `कैसे` and `kaise` are one word,
 * * punctuation becomes a space and runs of spaces collapse.
 */
object SearchKey {

    /** Shortest text worth building a key for. */
    private const val MIN_LENGTH = 1

    /** Key of [text]; empty when nothing comparable is left. */
    fun of(text: String): String {
        if (text.isEmpty()) return ""
        val transliterated = Devanagari.transliterate(text)
        val builder = StringBuilder(transliterated.length)
        var pendingSpace = false
        for (character in transliterated) {
            when {
                character.isLetterOrDigit() -> {
                    if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
                    pendingSpace = false
                    builder.append(fold(character))
                }

                character.isWhitespace() || isPunctuation(character) -> pendingSpace = builder.isNotEmpty()
            }
        }
        return builder.toString()
    }

    /** Keys of every word of [text], in order. */
    fun words(text: String): List<String> = of(text).split(' ').filter { it.isNotEmpty() }

    /**
     * Key with runs of the same letter collapsed to one.
     *
     * Two things produce doubled letters that a person does not hear: the anusvara of Devanagari,
     * which reads as a nasal on top of the letter before it, and people who type `bhaai` for
     * `bhai`. Comparing this form on both sides makes both of those match.
     */
    fun compact(text: String): String {
        val key = of(text)
        if (key.length < 2) return key
        val builder = StringBuilder(key.length)
        var previous = NO_PREVIOUS
        for (character in key) {
            if (character == previous) continue
            previous = character
            builder.append(character)
        }
        return builder.toString()
    }

    /** Compacted keys of every word of [text], in order. */
    fun compactWords(text: String): List<String> = compact(text).split(' ').filter { it.isNotEmpty() }

    private const val NO_PREVIOUS = '\u0000'

    /**
     * Lower case without the accent.
     *
     * Done by hand for the Latin letters that actually appear in names, because the platform
     * normalizer is not available below the version this application supports.
     */
    private fun fold(character: Char): Char {
        val lower = lowercase(character)
        return when (lower) {
            in 'à'..'å' -> 'a'
            'ç' -> 'c'
            in 'è'..'ë' -> 'e'
            in 'ì'..'ï' -> 'i'
            'ñ' -> 'n'
            in 'ò'..'ö', 'ø' -> 'o'
            in 'ù'..'ü' -> 'u'
            'ý', 'ÿ' -> 'y'
            'ß' -> 's'
            'đ' -> 'd'
            'ł' -> 'l'
            'ą' -> 'a'
            'ę' -> 'e'
            'ś' -> 's'
            'ż', 'ź' -> 'z'
            'ć' -> 'c'
            'ń' -> 'n'
            'ů' -> 'u'
            'ř' -> 'r'
            'š' -> 's'
            'ž' -> 'z'
            'ť' -> 't'
            'ď' -> 'd'
            else -> lower
        }
    }

    /** Lower case of one character, including the capitals that are not ASCII. */
    private fun lowercase(character: Char): Char = when (character) {
        in 'A'..'Z' -> character + CASE_DISTANCE
        'Ł' -> 'ł'
        'Đ' -> 'đ'
        'Ą' -> 'ą'
        'Ę' -> 'ę'
        'Ś' -> 'ś'
        'Ż' -> 'ż'
        'Ź' -> 'ź'
        'Ć' -> 'ć'
        'Ń' -> 'ń'
        'Ů' -> 'ů'
        'Ř' -> 'ř'
        'Š' -> 'š'
        'Ž' -> 'ž'
        'Ť' -> 'ť'
        'Ď' -> 'ď'
        else -> character
    }

    private fun isPunctuation(character: Char): Boolean =
        character in PUNCTUATION || Character.getType(character).let { type ->
            type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.MATH_SYMBOL.toInt()
        }

    private const val CASE_DISTANCE = 32

    private const val PUNCTUATION = ".,;:!?¡¿'\"`´^¨~|/\\()[]{}<>@#\$%&*+=_-–—…·«»“”‘’"

    /** True when [value] is short enough to be a typo of nothing at all. */
    fun isUsable(value: String): Boolean = value.length >= MIN_LENGTH
}
