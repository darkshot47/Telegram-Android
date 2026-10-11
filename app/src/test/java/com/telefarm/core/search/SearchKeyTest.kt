package com.telefarm.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one form of a text that search compares. */
class SearchKeyTest {

    @Test
    fun `case does not matter`() {
        assertEquals(SearchKey.of("Ramesh Kumar"), SearchKey.of("ramesh kumar"))
        assertEquals("ramesh kumar", SearchKey.of("RAMESH KUMAR"))
    }

    @Test
    fun `accents do not matter`() {
        assertEquals("jose", SearchKey.of("José"))
        assertEquals("francois", SearchKey.of("François"))
        assertEquals("lodz", SearchKey.of("Łódź"))
    }

    @Test
    fun `punctuation becomes a space and runs of spaces collapse`() {
        assertEquals("a b c", SearchKey.of("a, b -- c!"))
        assertEquals("hello world", SearchKey.of("  hello    world  "))
    }

    @Test
    fun `devanagari and its latin reading share one key`() {
        assertEquals(SearchKey.of("kaise ho"), SearchKey.of("कैसे हो"))
        assertEquals(SearchKey.of("namaste"), SearchKey.of("नमस्ते"))
        assertEquals(SearchKey.of("meeting kal 3 baje"), SearchKey.of("meeting कल ३ बजे"))
    }

    @Test
    fun `the key of an empty text is empty`() {
        assertEquals("", SearchKey.of(""))
        assertEquals("", SearchKey.of("   "))
        assertEquals("", SearchKey.of("..."))
    }

    @Test
    fun `words are the words of the key`() {
        assertEquals(listOf("kaise", "ho"), SearchKey.words("कैसे हो?"))
        assertEquals(emptyList<String>(), SearchKey.words("  "))
    }

    @Test
    fun `compact collapses a doubled letter`() {
        assertEquals("hindi", SearchKey.compact("हिन्दी"))
        assertEquals("hindi", SearchKey.compact("hindii"))
        assertEquals("bhai", SearchKey.compact("bhaai"))
    }

    @Test
    fun `compact leaves a single letter alone`() {
        assertEquals("a", SearchKey.compact("आ"))
        assertEquals("", SearchKey.compact(""))
    }

    @Test
    fun `compact keeps two different words apart`() {
        assertEquals("kala", SearchKey.compact("काला"))
        assertEquals("hindi", SearchKey.compact("हिंदी"))
        assertEquals(false, SearchKey.compact("काला") == SearchKey.compact("हिंदी"))
    }
}
