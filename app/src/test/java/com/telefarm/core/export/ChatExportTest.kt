package com.telefarm.core.export

import com.telefarm.data.model.MessageContentUi
import com.telefarm.data.model.MessageUi
import com.telefarm.data.model.PreviewKind
import com.telefarm.data.model.SendState
import com.telefarm.data.model.StickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writing a conversation out as a document. */
class ChatExportTest {

    @Test
    fun `a text message is written with its clock and its writer`() {
        val text = ChatExport.export(
            "Kamla",
            listOf(message(1, at(9, 5), "namaste")),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.contains("Kamla"))
        assertTrue(text, text.contains("[09:05] You: namaste"))
        assertTrue(text, text.contains("1 message"))
    }

    @Test
    fun `somebody else's message carries their name`() {
        val text = ChatExport.export(
            "Kamla",
            listOf(message(1, at(9, 5), "namaste", sender = "Kamla Devi", outgoing = false)),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.contains("Kamla Devi: namaste"))
    }

    @Test
    fun `a day heading appears once per day`() {
        val text = ChatExport.export(
            "Chat",
            listOf(
                message(1, at(9, 0), "morning"),
                message(2, at(9, 30), "still morning"),
                message(3, at(9, 0) + 86_400, "next day")
            ),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.contains("9 October 2025"))
        assertTrue(text, text.contains("10 October 2025"))
        assertEquals(1, Regex("9 October 2025").findAll(text).count())
        assertEquals(1, Regex("10 October 2025").findAll(text).count())
    }

    @Test
    fun `messages come out in the order they were written`() {
        val text = ChatExport.export(
            "Chat",
            listOf(message(2, at(11, 0), "second"), message(1, at(10, 0), "first")),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.indexOf("first") < text.indexOf("second"))
    }

    @Test
    fun `a range leaves out what is outside it`() {
        val messages = listOf(
            message(1, at(9, 0), "morning"),
            message(2, at(12, 0), "noon"),
            message(3, at(15, 0), "afternoon")
        )
        val text = ChatExport.export(
            "Chat",
            messages,
            ExportOptions(
                format = ExportFormat.TEXT,
                fromSeconds = at(8, 0).toLong(),
                toSeconds = at(13, 0).toLong()
            )
        )
        assertTrue(text, text.contains("morning"))
        assertTrue(text, text.contains("noon"))
        assertFalse(text, text.contains("afternoon"))
        assertTrue(text, text.contains("2 messages"))
    }

    @Test
    fun `a range that takes everything takes everything`() {
        val messages = listOf(message(1, at(9, 0), "a"), message(2, at(10, 0), "b"))
        val text = ChatExport.export("Chat", messages, ExportOptions(format = ExportFormat.TEXT))
        assertTrue(text, text.contains("2 messages"))
    }

    @Test
    fun `a message that was edited says so`() {
        val text = ChatExport.export(
            "Chat",
            listOf(message(1, at(9, 0), "fixed", edited = true)),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.contains("(edited)"))
    }

    @Test
    fun `media is written as a placeholder with its caption`() {
        val text = ChatExport.export(
            "Chat",
            listOf(message(1, at(9, 0), content = MessageContentUi.Photo(null, null, "the view"))),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.contains("[Photo]"))
        assertTrue(text, text.contains("the view"))
    }

    @Test
    fun `media off exports only words`() {
        val messages = listOf(
            message(1, at(9, 0), content = MessageContentUi.Photo(null, null, "the view")),
            message(2, at(9, 1), content = MessageContentUi.Photo(null, null, null)),
            message(3, at(9, 2), "just words")
        )
        val text = ChatExport.export(
            "Chat",
            messages,
            ExportOptions(format = ExportFormat.TEXT, includeMedia = false)
        )
        assertTrue(text, text.contains("the view"))
        assertFalse(text, text.contains("[Photo]"))
        // A message that was only a photo has nothing left to say, so it is left out entirely.
        assertTrue(text, text.contains("2 messages"))
        assertTrue(text, text.contains("just words"))
    }

    @Test
    fun `every kind of media gets a word for itself`() {
        val text = ChatExport.export(
            "Chat",
            listOf(
                message(1, at(9, 0), content = MessageContentUi.Video(null, null, null, 64, null)),
                message(2, at(9, 1), content = MessageContentUi.Document(null, "report.pdf", null, 12, null)),
                message(3, at(9, 2), content = MessageContentUi.Voice(null, 12)),
                message(4, at(9, 3), content = MessageContentUi.Audio(null, "Song", "Singer", 200)),
                message(5, at(9, 4), content = MessageContentUi.Sticker(null, StickerKind.ANIMATED)),
                message(6, at(9, 5), content = MessageContentUi.Other(PreviewKind.CALL))
            ),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertTrue(text, text.contains("[Video 1:04]"))
        assertTrue(text, text.contains("[File report.pdf]"))
        assertTrue(text, text.contains("[Voice message 0:12]"))
        assertTrue(text, text.contains("[Audio Singer - Song 3:20]"))
        assertTrue(text, text.contains("[Animated sticker]"))
        assertTrue(text, text.contains("[Call]"))
    }

    @Test
    fun `an empty conversation still writes a document`() {
        val text = ChatExport.export("Empty", emptyList(), ExportOptions(format = ExportFormat.TEXT))
        assertTrue(text, text.contains("Empty"))
        assertTrue(text, text.contains("0 messages"))
    }

    @Test
    fun `html escapes what people wrote`() {
        val html = ChatExport.export(
            "<script>alert(1)</script>",
            listOf(message(1, at(9, 0), "a < b & c > d \"e\" 'f'")),
            ExportOptions(format = ExportFormat.HTML)
        )
        assertFalse(html, html.contains("<script>"))
        assertTrue(html, html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html, html.contains("a &lt; b &amp; c &gt; d &quot;e&quot; &#39;f&#39;"))
    }

    @Test
    fun `html keeps a line break as a break`() {
        val html = ChatExport.export(
            "Chat",
            listOf(message(1, at(9, 0), "one\ntwo")),
            ExportOptions(format = ExportFormat.HTML)
        )
        assertTrue(html, html.contains("one<br>two"))
    }

    @Test
    fun `html is a whole page`() {
        val html = ChatExport.export("Chat", listOf(message(1, at(9, 0), "hi")), ExportOptions())
        assertTrue(html, html.startsWith("<!DOCTYPE html>"))
        assertTrue(html, html.contains("<html lang=\"en\">"))
        assertTrue(html, html.contains("</html>"))
        assertTrue(html, html.contains("<style>"))
    }

    @Test
    fun `html says which way a message went`() {
        val html = ChatExport.export(
            "Chat",
            listOf(
                message(1, at(9, 0), "mine", outgoing = true),
                message(2, at(9, 1), "theirs", outgoing = false)
            ),
            ExportOptions()
        )
        assertTrue(html, html.contains("class=\"msg out\""))
        assertTrue(html, html.contains("class=\"msg in\""))
    }

    @Test
    fun `a file name has nothing a file system would object to`() {
        assertEquals("Chat.html", ChatExport.fileName("Chat", ExportOptions()))
        assertEquals("a b.txt", ChatExport.fileName("a/b", ExportOptions(format = ExportFormat.TEXT)))
        assertEquals("a b.txt", ChatExport.fileName("a\\b", ExportOptions(format = ExportFormat.TEXT)))
        assertEquals("a b.txt", ChatExport.fileName("a:b", ExportOptions(format = ExportFormat.TEXT)))
        assertEquals("chat.html", ChatExport.fileName("", ExportOptions()))
        assertEquals("chat.html", ChatExport.fileName("///", ExportOptions()))
    }

    @Test
    fun `a long title is shortened`() {
        val name = ChatExport.fileName("x".repeat(200), ExportOptions())
        assertEquals(ChatExport.MAX_FILE_NAME_LENGTH + ".html".length, name.length)
    }

    @Test
    fun `a title keeps its own writing`() {
        assertEquals("कमला और सुरेश.txt", ChatExport.fileName("कमला और सुरेश", ExportOptions(format = ExportFormat.TEXT)))
        assertEquals("नमस्ते 🎉.html", ChatExport.fileName("नमस्ते 🎉", ExportOptions()))
    }

    @Test
    fun `a control character cannot get into the file name`() {
        val name = ChatExport.sanitiseFileName("a\u0000b\u001fc")
        assertEquals("a b c", name)
        assertFalse(name, name.any { it.code < 0x20 })
    }

    @Test
    fun `the exported text carries no angle bracket that was typed`() {
        for (source in listOf("<b>", "</body>", "<img src=x onerror=alert(1)>", "&&&", "\"'")) {
            val html = ChatExport.export("T", listOf(message(1, at(9, 0), source)), ExportOptions())
            val body = html.substringAfter("<div class=\"text\">").substringBefore("</div>")
            assertFalse("$source leaked as <$source>", body.contains("<") || body.contains(">"))
            assertFalse("$source leaked a quote", body.contains("\"") || body.contains("'"))
        }
    }

    @Test
    fun `no message id or account detail is written out`() {
        val text = ChatExport.export(
            "Chat",
            listOf(message(4_711_000_000_123L, at(9, 0), "hello", sender = "Kamla")),
            ExportOptions(format = ExportFormat.TEXT)
        )
        assertFalse(text, text.contains("4711000000123"))
    }

    @Test
    fun `the zone the dates are written in is the one asked for`() {
        val utc = ChatExport.export("C", listOf(message(1, 0, "x")), ExportOptions(format = ExportFormat.TEXT))
        val ist = ChatExport.export(
            "C",
            listOf(message(1, 0, "x")),
            ExportOptions(format = ExportFormat.TEXT, utcOffsetMinutes = 330)
        )
        assertTrue(utc, utc.contains("[00:00]"))
        assertTrue(ist, ist.contains("[05:30]"))
    }

    /** Midnight UTC on 9 October 2025, so [at] reads as the clock time it is given. */
    private val midnight = 1_759_968_000

    private fun at(hour: Int, minute: Int): Int = midnight + hour * 3600 + minute * 60

    private fun message(
        id: Long,
        date: Int,
        text: String? = null,
        sender: String? = null,
        outgoing: Boolean = true,
        edited: Boolean = false,
        content: MessageContentUi? = null
    ) = MessageUi(
        id = id,
        chatId = 42,
        senderId = if (outgoing) 1 else 2,
        senderName = sender,
        showSender = true,
        isOutgoing = outgoing,
        date = date,
        isEdited = edited,
        sendState = SendState.SENT,
        canRetry = false,
        isRead = true,
        reactions = emptyList(),
        content = content ?: MessageContentUi.Text(text.orEmpty())
    )
}
