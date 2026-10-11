package com.telefarm.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How Devanagari reads in Latin letters.
 *
 * The expectations are what a Hindi reader says, not what the character table alone would
 * produce: the inherent vowel of the last consonant of a word is silent.
 */
class DevanagariTest {

    @Test
    fun `reads a common greeting`() {
        assertEquals("namaste", Devanagari.transliterate("नमस्ते"))
    }

    @Test
    fun `reads a question the way it is spoken`() {
        assertEquals("kaise ho", Devanagari.transliterate("कैसे हो"))
    }

    @Test
    fun `drops the inherent vowel at the end of a word only`() {
        // The first क carries its a because a consonant follows; the last one does not.
        assertEquals("kamal", Devanagari.transliterate("कमल"))
        assertEquals("kakshaa", Devanagari.transliterate("कक्षा"))
    }

    @Test
    fun `a virama cancels the vowel of the consonant before it`() {
        assertEquals("vidyaalay", Devanagari.transliterate("विद्यालय"))
        assertEquals("hindii", Devanagari.transliterate("हिन्दी"))
    }

    @Test
    fun `the anusvara reads as the nasal it stands for`() {
        assertEquals("hind", Devanagari.transliterate("हिंद"))
        assertEquals("hindii", Devanagari.transliterate("हिंदी"))
    }

    @Test
    fun `nukta forms read as the sound they stand for`() {
        assertEquals("zindagii", Devanagari.transliterate("ज़िंदगी"))
        assertEquals("film", Devanagari.transliterate("फ़िल्म"))
        assertEquals("qilaa", Devanagari.transliterate("क़िला"))
    }

    @Test
    fun `independent vowels read on their own`() {
        assertEquals("aa", Devanagari.transliterate("आ"))
        assertEquals("ii", Devanagari.transliterate("ई"))
        assertEquals("ai", Devanagari.transliterate("ऐ"))
    }

    @Test
    fun `devanagari digits become latin digits`() {
        assertEquals("2026", Devanagari.transliterate("२०२६"))
        assertEquals("room 5", Devanagari.transliterate("room ५"))
    }

    @Test
    fun `a mixed sentence keeps its english words as they are`() {
        assertEquals("meeting kal 3 baje", Devanagari.transliterate("meeting कल ३ बजे"))
    }

    @Test
    fun `text without the script is returned unchanged`() {
        assertEquals("Ramesh Kumar", Devanagari.transliterate("Ramesh Kumar"))
        assertFalse(Devanagari.contains("Ramesh Kumar"))
        assertTrue(Devanagari.contains("रमेश"))
    }

    @Test
    fun `a nukta written as two characters reads like the one character form`() {
        // ज followed by a combining nukta, which is what a keyboard produces.
        val decomposed = "\u091C\u093C\u093F\u0902\u0926\u0917\u0940"
        assertEquals(Devanagari.transliterate("ज़िंदगी"), Devanagari.transliterate(decomposed))
    }

    @Test
    fun `a nukta on its own does not survive into the reading`() {
        assertTrue(!Devanagari.transliterate("क\u093C").contains("\u093C"))
    }

    @Test
    fun `an empty text stays empty`() {
        assertEquals("", Devanagari.transliterate(""))
    }
}
