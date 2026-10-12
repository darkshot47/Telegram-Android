package com.telefarm.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** Putting formatting markers into a message while it is written. */
class ComposerEditTest {

    @Test
    fun `with nothing selected the markers go in and the caret sits between them`() {
        val edit = ComposerFormatting.bold("", 0, 0)
        assertEquals("****", edit.text)
        assertEquals(2, edit.caretStart)
        assertEquals(2, edit.caretEnd)
    }

    @Test
    fun `words selected are wrapped`() {
        val edit = ComposerFormatting.bold("hello", 0, 5)
        assertEquals("**hello**", edit.text)
        assertEquals(2, edit.caretStart)
        assertEquals(7, edit.caretEnd)
    }

    @Test
    fun `part of a message can be wrapped and the rest stays put`() {
        val edit = ComposerFormatting.italic("say hello now", 4, 9)
        assertEquals("say __hello__ now", edit.text)
    }

    @Test
    fun `words already wrapped are unwrapped, so the button is a toggle`() {
        val edit = ComposerFormatting.bold("**hello**", 2, 7)
        assertEquals("hello", edit.text)
        assertEquals(0, edit.caretStart)
        assertEquals(5, edit.caretEnd)
    }

    @Test
    fun `wrapping and unwrapping leaves the words exactly as they were`() {
        val text = "a message worth keeping"
        val wrapped = ComposerFormatting.code(text, 2, 9)
        val unwrapped = ComposerFormatting.code(wrapped.text, wrapped.caretStart, wrapped.caretEnd)
        assertEquals(text, unwrapped.text)
    }

    @Test
    fun `a selection that includes the markers is unwrapped too`() {
        val edit = ComposerFormatting.bold("**hi**", 0, 6)
        assertEquals("hi", edit.text)
    }

    @Test
    fun `each formatting button uses its own marker`() {
        assertEquals("`x`", ComposerFormatting.code("x", 0, 1).text)
        assertEquals("~~x~~", ComposerFormatting.strikethrough("x", 0, 1).text)
        assertEquals("||x||", ComposerFormatting.spoiler("x", 0, 1).text)
        assertEquals("__x__", ComposerFormatting.italic("x", 0, 1).text)
        assertEquals("**x**", ComposerFormatting.bold("x", 0, 1).text)
    }

    @Test
    fun `a caret past the end of the text is clamped rather than trusted`() {
        val edit = ComposerFormatting.bold("ab", 9, 9)
        assertEquals("ab****", edit.text)
        assertEquals(4, edit.caretStart)
    }

    @Test
    fun `a negative caret is clamped to the start`() {
        val edit = ComposerFormatting.bold("ab", -3, -1)
        assertEquals("****ab", edit.text)
        assertEquals(2, edit.caretStart)
    }

    @Test
    fun `a reversed selection is read the right way round`() {
        val edit = ComposerFormatting.bold("hello", 5, 0)
        assertEquals("**hello**", edit.text)
    }

    @Test
    fun `a selection past the end of the text wraps what is there`() {
        val edit = ComposerFormatting.bold("hi", 0, 99)
        assertEquals("**hi**", edit.text)
    }

    @Test
    fun `wrapping keeps the words selected so another button can follow`() {
        val bold = ComposerFormatting.bold("hello", 0, 5)
        assertEquals(bold.caretEnd - bold.caretStart, 5)
    }

    @Test
    fun `two formattings can be nested`() {
        val bold = ComposerFormatting.bold("hello", 0, 5)
        val nested = ComposerFormatting.italic(bold.text, bold.caretStart, bold.caretEnd)
        assertEquals("**__hello__**", nested.text)
    }

    @Test
    fun `text around the caret is not disturbed`() {
        val edit = ComposerFormatting.spoiler("before after", 7, 12)
        assertEquals("before ||after||", edit.text)
    }

    @Test
    fun `an empty marker is refused rather than looping`() {
        val result = runCatching { ComposerFormatting.toggle("x", 0, 1, "") }
        assertEquals(true, result.isFailure)
    }
}
