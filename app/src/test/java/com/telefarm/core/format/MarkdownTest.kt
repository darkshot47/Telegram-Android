package com.telefarm.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the markup a person types into the text plus runs that Telegram asks for. */
class MarkdownTest {

    @Test
    fun `plain text has no runs`() {
        val result = Markdown.parse("hello there")
        assertEquals("hello there", result.text)
        assertEquals(emptyList<TextSpan>(), result.spans)
        assertFalse(result.hasFormatting)
    }

    @Test
    fun `an empty text stays empty`() {
        assertEquals(FormattedText("", emptyList()), Markdown.parse(""))
    }

    @Test
    fun `each marker makes its own kind of run`() {
        assertEquals(listOf(TextSpan(0, 4, TextKind.BOLD)), Markdown.parse("**bold**").spans)
        assertEquals("italic", Markdown.parse("__italic__").text)
        assertEquals(TextKind.ITALIC, Markdown.parse("__italic__").spans.single().kind)
        assertEquals(TextKind.STRIKETHROUGH, Markdown.parse("~~gone~~").spans.single().kind)
        assertEquals(TextKind.SPOILER, Markdown.parse("||spoiler||").spans.single().kind)
        assertEquals(TextKind.CODE, Markdown.parse("`code`").spans.single().kind)
        assertEquals(TextKind.PRE, Markdown.parse("```\nblock\n```").spans.single().kind)
    }

    @Test
    fun `the markers are taken out of the text`() {
        val result = Markdown.parse("a **b** c")
        assertEquals("a b c", result.text)
        assertEquals(listOf(TextSpan(2, 1, TextKind.BOLD)), result.spans)
    }

    @Test
    fun `several runs keep their places`() {
        val result = Markdown.parse("**bold** and __it__")
        assertEquals("bold and it", result.text)
        assertEquals(
            listOf(TextSpan(0, 4, TextKind.BOLD), TextSpan(9, 2, TextKind.ITALIC)),
            result.spans
        )
    }

    @Test
    fun `italic inside bold is a run inside a run`() {
        val result = Markdown.parse("**bold __and italic__**")
        assertEquals("bold and italic", result.text)
        assertEquals(
            listOf(TextSpan(0, 15, TextKind.BOLD), TextSpan(5, 10, TextKind.ITALIC)),
            result.spans
        )
    }

    @Test
    fun `code inside bold keeps both runs`() {
        val result = Markdown.parse("**bold `code` bold**")
        assertEquals("bold code bold", result.text)
        assertEquals(
            listOf(TextSpan(0, 14, TextKind.BOLD), TextSpan(5, 4, TextKind.CODE)),
            result.spans
        )
    }

    @Test
    fun `nothing inside code is formatted`() {
        val result = Markdown.parse("`**not bold**`")
        assertEquals("**not bold**", result.text)
        assertEquals(listOf(TextSpan(0, 12, TextKind.CODE)), result.spans)
    }

    @Test
    fun `code in the middle of a sentence`() {
        val result = Markdown.parse("pre `in **bold**` post")
        assertEquals("pre in **bold** post", result.text)
        assertEquals(listOf(TextSpan(4, 11, TextKind.CODE)), result.spans)
    }

    @Test
    fun `a link becomes a run with a url`() {
        val result = Markdown.parse("[Telegram](https://telegram.org)")
        assertEquals("Telegram", result.text)
        assertEquals(
            listOf(TextSpan(0, 8, TextKind.LINK, "https://telegram.org")),
            result.spans
        )
    }

    @Test
    fun `text around a link keeps its place`() {
        val result = Markdown.parse("see [it](http://x.io) now")
        assertEquals("see it now", result.text)
        assertEquals(listOf(TextSpan(4, 2, TextKind.LINK, "http://x.io")), result.spans)
    }

    @Test
    fun `bold markers in a link label are taken out but do not make a run`() {
        // The format does not let a bold run sit inside a link, so the words are kept and the
        // asterisks are not.
        val result = Markdown.parse("[**bold link**](https://x.com)")
        assertEquals("bold link", result.text)
        assertEquals(listOf(TextSpan(0, 9, TextKind.LINK, "https://x.com")), result.spans)
    }

    @Test
    fun `a marker with no closer is ordinary text`() {
        assertEquals("**unclosed", Markdown.parse("**unclosed").text)
        assertEquals(emptyList<TextSpan>(), Markdown.parse("**unclosed").spans)
        assertEquals("closed**", Markdown.parse("closed**").text)
        assertEquals("````", Markdown.parse("````").text)
        assertEquals("**", Markdown.parse("**").text)
        assertEquals("||", Markdown.parse("||").text)
    }

    @Test
    fun `arithmetic is not a failed attempt at bold`() {
        val result = Markdown.parse("2 ** 3")
        assertEquals("2 ** 3", result.text)
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `something that looks like a link but is not one stays as typed`() {
        for (source in listOf("[no url]()", "[broken](http://x y)", "not a [link", "[label](http://(bad)")) {
            assertEquals(source, Markdown.parse(source).text)
            assertEquals(emptyList<TextSpan>(), Markdown.parse(source).spans)
        }
    }

    @Test
    fun `a one character link is still a link`() {
        val result = Markdown.parse("[a](b)")
        assertEquals("a", result.text)
        assertEquals(listOf(TextSpan(0, 1, TextKind.LINK, "b")), result.spans)
    }

    @Test
    fun `two runs next to each other do not merge`() {
        val result = Markdown.parse("**a****b**")
        assertEquals("ab", result.text)
        assertEquals(
            listOf(TextSpan(0, 1, TextKind.BOLD), TextSpan(1, 1, TextKind.BOLD)),
            result.spans
        )
    }

    @Test
    fun `a run covers the characters of a wide one correctly`() {
        // An emoji is two UTF-16 units, and the offsets are counted in those units, so the run
        // that starts after it starts at 3 and not at 2.
        val result = Markdown.parse("🎉 **party** 🎉")
        assertEquals("🎉 party 🎉", result.text)
        assertEquals(listOf(TextSpan(3, 5, TextKind.BOLD)), result.spans)
    }

    @Test
    fun `devanagari is formatted like anything else`() {
        val result = Markdown.parse("नमस्ते **दुनिया**")
        assertEquals("नमस्ते दुनिया", result.text)
        assertEquals(listOf(TextSpan(7, 6, TextKind.BOLD)), result.spans)
    }

    @Test
    fun `the longer marker wins at one position`() {
        // ``` before ` so a block of code is not read as three code runs.
        val result = Markdown.parse("```x```")
        assertEquals(listOf(TextSpan(0, 1, TextKind.PRE)), result.spans)
    }

    @Test
    fun `has formatting says what the name says`() {
        assertFalse(Markdown.hasFormatting("nothing here"))
        assertFalse(Markdown.hasFormatting("2 ** 3"))
        assertTrue(Markdown.hasFormatting("**bold**"))
        assertTrue(Markdown.hasFormatting("a `code` b"))
    }

    @Test
    fun `every run is inside the text and nothing runs past its end`() {
        for (source in SAMPLES) {
            val result = Markdown.parse(source)
            for (span in result.spans) {
                assertTrue("$source: run $span starts before the text", span.offset >= 0)
                assertTrue(
                    "$source: run $span runs past the text of ${result.text.length}",
                    span.end <= result.text.length
                )
                assertTrue("$source: run $span is empty", span.length > 0)
            }
        }
    }

    @Test
    fun `runs are listed by where they start, outermost first`() {
        for (source in SAMPLES) {
            val spans = Markdown.parse(source).spans
            for (index in 1 until spans.size) {
                val before = spans[index - 1]
                val after = spans[index]
                assertTrue(
                    "$source: $after is listed before $before",
                    after.offset > before.offset || (after.offset == before.offset && after.length <= before.length)
                )
            }
        }
    }

    @Test
    fun `no run sits inside code, a block of code, or a link`() {
        // The format says code and a link cannot hold other runs, and the app refuses the whole
        // message when they do.
        val closed = setOf(TextKind.CODE, TextKind.PRE, TextKind.LINK)
        for (source in SAMPLES) {
            val spans = Markdown.parse(source).spans
            for (outer in spans.filter { it.kind in closed }) {
                for (inner in spans) {
                    assertFalse(
                        "$source: ${inner.kind} inside ${outer.kind}",
                        inner.isInside(outer)
                    )
                }
            }
        }
    }

    @Test
    fun `runs do not partly overlap`() {
        for (source in SAMPLES) {
            val spans = Markdown.parse(source).spans
            for (a in spans) {
                for (b in spans) {
                    if (a == b || a.isInside(b) || b.isInside(a)) continue
                    val overlaps = a.offset < b.end && b.offset < a.end
                    assertFalse("$source: ${a.kind}@$a and ${b.kind}@$b partly overlap", overlaps)
                }
            }
        }
    }

    @Test
    fun `a link never carries a url of another kind`() {
        for (source in SAMPLES) {
            for (span in Markdown.parse(source).spans) {
                if (span.kind == TextKind.LINK) {
                    assertTrue("$source: link without a url", !span.url.isNullOrEmpty())
                } else {
                    assertNull("$source: ${span.kind} carrying a url", span.url)
                }
            }
        }
    }

    private companion object {
        val SAMPLES = listOf(
            "",
            "hello",
            "**bold**",
            "__italic__",
            "~~gone~~",
            "||spoiler||",
            "`code`",
            "```\nblock\n```",
            "a **b** c",
            "**bold __and italic__**",
            "**bold `code` bold**",
            "`**not bold**`",
            "[Telegram](https://telegram.org)",
            "[**bold link**](https://x.com)",
            "see [it](http://x.io) now",
            "2 ** 3",
            "**unclosed",
            "closed**",
            "**a****b**",
            "***triple***",
            "__a__b__c__",
            "🎉 **party** 🎉",
            "नमस्ते **दुनिया**",
            "**bold** and __it__",
            "[broken](http://x y)",
            "```x```",
            "****",
            "** ** **",
            "a||b||c||d||e"
        )
    }
}
