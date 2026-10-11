package com.telefarm.core.search

/** How strongly a text answers a query; zero means it does not answer it. */
object SearchRanker {

    /** Nothing to compare. */
    const val NO_MATCH = 0

    /** Same text, apart from case, script and punctuation. */
    const val EXACT = 100

    /** The text starts with the query, which is what typing a name produces. */
    const val PREFIX = 80

    /** A word of the text starts with the query. */
    const val WORD_PREFIX = 70

    /** The query is somewhere inside the text. */
    const val CONTAINS = 50

    /** The same word with a doubled letter somewhere, such as the anusvara of Devanagari. */
    const val COMPACT = 40

    /**
     * The letters of the query appear in order inside the text, which is what a thumb typing
     * fast produces: `rmsh` for `Ramesh`.
     */
    const val SUBSEQUENCE = 20

    /** Score of [text] against [query]; [NO_MATCH] when the text does not answer the query. */
    fun score(query: String, text: String): Int {
        val queryKey = SearchKey.of(query)
        if (queryKey.isEmpty()) return NO_MATCH
        val textKey = SearchKey.of(text)
        if (textKey.isEmpty()) return NO_MATCH

        if (textKey == queryKey) return EXACT
        if (textKey.startsWith(queryKey)) return PREFIX

        val words = textKey.split(' ')
        if (words.any { it.startsWith(queryKey) }) return WORD_PREFIX
        if (textKey.contains(queryKey)) return CONTAINS

        val queryCompact = SearchKey.compact(query)
        val textCompact = SearchKey.compact(text)
        if (textCompact == queryCompact) return COMPACT
        if (textCompact.contains(queryCompact)) return COMPACT

        if (words.any { isSubsequence(queryKey, it) }) return SUBSEQUENCE
        if (SearchKey.compactWords(text).any { isSubsequence(queryCompact, it) }) return SUBSEQUENCE
        return NO_MATCH
    }

    /** True when [text] answers [query] at all. */
    fun matches(query: String, text: String): Boolean = score(query, text) > NO_MATCH

    /**
     * [items] that answer [query], strongest answer first.
     *
     * Items that do not answer it are left out. The order of items with the same score is the
     * order they came in, so a list the user already knows is not shuffled by a search.
     */
    fun <T> rank(query: String, items: List<T>, textOf: (T) -> String): List<T> {
        val scored = items.mapNotNull { item ->
            val value = score(query, textOf(item))
            if (value > NO_MATCH) item to value else null
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /** True when every character of [needle] appears in [haystack] in order. */
    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return true
        var index = 0
        for (character in haystack) {
            if (character == needle[index]) {
                index++
                if (index == needle.length) return true
            }
        }
        return false
    }
}
