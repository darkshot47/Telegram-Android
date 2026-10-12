package com.telefarm.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a notification says about the message behind it. */
class NotificationPreviewTextTest {

    @Test
    fun `full shows the message`() {
        assertEquals("hello", NotificationPreviewText.content(NotificationPreview.FULL, "Anu", "hello"))
    }

    @Test
    fun `sender shows who wrote it and not what they wrote`() {
        assertEquals("Anu", NotificationPreviewText.content(NotificationPreview.SENDER, "Anu", "hello"))
    }

    @Test
    fun `none shows only that something arrived`() {
        assertEquals(
            NotificationPreviewText.HIDDEN,
            NotificationPreviewText.content(NotificationPreview.NONE, "Anu", "hello")
        )
    }

    @Test
    fun `a message with no words still says something`() {
        assertEquals(
            NotificationPreviewText.HIDDEN,
            NotificationPreviewText.content(NotificationPreview.FULL, "Anu", null)
        )
    }

    @Test
    fun `a blank message is treated as no message`() {
        assertEquals(
            NotificationPreviewText.HIDDEN,
            NotificationPreviewText.content(NotificationPreview.FULL, "Anu", "   ")
        )
    }

    @Test
    fun `a sender with no name is named rather than left blank`() {
        assertEquals(
            NotificationPreviewText.UNKNOWN_SENDER,
            NotificationPreviewText.content(NotificationPreview.SENDER, null, "hello")
        )
    }

    @Test
    fun `a sender name of only spaces is no name`() {
        assertEquals(
            NotificationPreviewText.UNKNOWN_SENDER,
            NotificationPreviewText.content(NotificationPreview.SENDER, "  ", "hello")
        )
    }

    @Test
    fun `surrounding spaces are taken off before anything is shown`() {
        assertEquals("hello", NotificationPreviewText.content(NotificationPreview.FULL, " Anu ", "  hello  "))
    }

    @Test
    fun `no notification is ever empty`() {
        for (mode in NotificationPreview.entries) {
            for (sender in listOf(null, "", "  ", "Anu")) {
                for (text in listOf(null, "", "  ", "hello")) {
                    val content = NotificationPreviewText.content(mode, sender, text)
                    assertTrue("$mode/$sender/$text was blank", content.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `full is the only setting that puts the message on the screen`() {
        assertTrue(NotificationPreviewText.revealsMessage(NotificationPreview.FULL, "hello"))
        assertFalse(NotificationPreviewText.revealsMessage(NotificationPreview.SENDER, "hello"))
        assertFalse(NotificationPreviewText.revealsMessage(NotificationPreview.NONE, "hello"))
    }

    @Test
    fun `full with no message reveals nothing`() {
        assertFalse(NotificationPreviewText.revealsMessage(NotificationPreview.FULL, null))
        assertFalse(NotificationPreviewText.revealsMessage(NotificationPreview.FULL, "  "))
    }

    @Test
    fun `there are exactly three settings`() {
        assertEquals(3, NotificationPreview.entries.size)
    }

    @Test
    fun `hidden is a fixed phrase rather than a per-message one`() {
        assertEquals(
            NotificationPreviewText.content(NotificationPreview.NONE, "Anu", "one"),
            NotificationPreviewText.content(NotificationPreview.NONE, "Ravi", "another")
        )
    }
}
