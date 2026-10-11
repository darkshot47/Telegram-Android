package com.telefarm.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How strongly a text answers a query. */
class SearchRankerTest {

    @Test
    fun `the same text in another script is an exact answer`() {
        assertEquals(SearchRanker.EXACT, SearchRanker.score("kaise ho", "कैसे हो"))
        assertEquals(SearchRanker.EXACT, SearchRanker.score("Kaise Ho?", "कैसे हो"))
    }

    @Test
    fun `a text that starts with the query is a strong answer`() {
        assertEquals(SearchRanker.PREFIX, SearchRanker.score("ram", "Ramesh Kumar"))
    }

    @Test
    fun `a word that starts with the query is weaker than the whole text`() {
        val score = SearchRanker.score("kumar", "Ramesh Kumar")
        assertEquals(SearchRanker.WORD_PREFIX, score)
        assertTrue(score < SearchRanker.PREFIX)
    }

    @Test
    fun `the query inside the text is weaker still`() {
        assertEquals(SearchRanker.CONTAINS, SearchRanker.score("mesh", "Ramesh Kumar"))
    }

    @Test
    fun `a doubled letter still matches on the compact form`() {
        assertTrue(SearchRanker.score("hindi", "हिन्दी") >= SearchRanker.COMPACT)
    }

    @Test
    fun `letters in order match even when some are missing`() {
        assertEquals(SearchRanker.SUBSEQUENCE, SearchRanker.score("rmsh", "Ramesh Kumar"))
    }

    @Test
    fun `an unrelated text is not an answer`() {
        assertEquals(SearchRanker.NO_MATCH, SearchRanker.score("zebra", "Ramesh Kumar"))
        assertFalse(SearchRanker.matches("zebra", "Ramesh Kumar"))
    }

    @Test
    fun `an empty query or text answers nothing`() {
        assertEquals(SearchRanker.NO_MATCH, SearchRanker.score("", "Ramesh"))
        assertEquals(SearchRanker.NO_MATCH, SearchRanker.score("ram", ""))
    }

    @Test
    fun `results are ordered strongest first and keep the order of equal scores`() {
        val items = listOf("Arun Ramesh", "Ramesh Kumar", "कैसे हो", "Suresh Mesh")
        val ranked = SearchRanker.rank("ram", items) { it }
        assertEquals(listOf("Ramesh Kumar", "Arun Ramesh"), ranked)
    }

    @Test
    fun `a search in devanagari finds the latin name and the other way round`() {
        val items = listOf("रमेश कुमार", "Sita Devi")
        assertEquals(listOf("रमेश कुमार"), SearchRanker.rank("ramesh", items) { it })
        assertEquals(listOf("रमेश कुमार"), SearchRanker.rank("रमेश", items) { it })
    }

    @Test
    fun `nothing matches when nothing answers the query`() {
        assertEquals(emptyList<String>(), SearchRanker.rank("zebra", listOf("Ramesh", "Sita")) { it })
    }
}
