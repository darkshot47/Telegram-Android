package com.telefarm.core.format

/** What a run of text means. The names are Telegram's, not Markdown's. */
enum class TextKind {

    /** `**bold**` */
    BOLD,

    /** `__italic__` */
    ITALIC,

    /** `~~struck through~~` */
    STRIKETHROUGH,

    /** `||hidden until tapped||` */
    SPOILER,

    /** `` `inline code` `` */
    CODE,

    /** ` ```a block of code``` ` */
    PRE,

    /** `[text](https://example.com)` */
    LINK
}

/**
 * A run of text that is formatted in one way.
 *
 * [offset] and [length] are in UTF-16 code units, which is what a Kotlin [String] index is and
 * what the wire format asks for, so an emoji inside the run counts as two - correctly.
 */
data class TextSpan(

    /** Index of the first character of the run, in the text the markers were removed from. */
    val offset: Int,

    /** Characters the run covers. */
    val length: Int,

    val kind: TextKind,

    /** Where a [TextKind.LINK] goes; null for every other kind. */
    val url: String? = null
) {

    /** Index just past the last character of the run. */
    val end: Int get() = offset + length

    /** True when this run starts inside [other] and ends before it does. */
    fun isInside(other: TextSpan): Boolean = offset >= other.offset && end <= other.end && this != other
}

/** Text with the markers taken out, and the runs that were between them. */
data class FormattedText(val text: String, val spans: List<TextSpan>) {

    /** True when anything was formatted. */
    val hasFormatting: Boolean get() = spans.isNotEmpty()
}

/**
 * Reads the markup a person types and produces the text to send with it.
 *
 * Telegram does not read Markdown; it takes a text plus a list of runs that say which characters
 * are bold. This is the translation between the two.
 *
 * A marker with no matching closer is left alone, because typing `2 ** 3` is arithmetic and not a
 * failed attempt at bold. Nothing here throws, whatever is typed.
 *
 * The nesting rules are the ones the format itself states, not a choice made here: code and a
 * link cannot contain other formatting, while bold, italic, strikethrough and a spoiler can
 * contain each other. So `` `**not bold**` `` sends the asterisks and no bold run, and
 * `**bold __and italic__**` sends two runs, one inside the other.
 */
object Markdown {

    /** Markers, longest first: at one position a longer marker wins over a shorter one inside it. */
    private val MARKERS = listOf(
        Marker("```", TextKind.PRE, parsesInside = false),
        Marker("**", TextKind.BOLD, parsesInside = true),
        Marker("__", TextKind.ITALIC, parsesInside = true),
        Marker("~~", TextKind.STRIKETHROUGH, parsesInside = true),
        Marker("||", TextKind.SPOILER, parsesInside = true),
        Marker("`", TextKind.CODE, parsesInside = false)
    )

    private data class Marker(val token: String, val kind: TextKind, val parsesInside: Boolean)

    /** One marker pair found in the source. */
    private data class Found(
        val start: Int,
        val end: Int,
        val innerStart: Int,
        val innerEnd: Int,
        val marker: Marker?,
        val url: String? = null
    )

    /** [source] with the markers removed and the runs listed. */
    fun parse(source: String): FormattedText {
        if (source.isEmpty()) return FormattedText("", emptyList())
        val text = StringBuilder(source.length)
        val spans = mutableListOf<TextSpan>()
        var index = 0
        while (index < source.length) {
            val found = findNext(source, index)
            if (found == null) {
                text.append(source, index, source.length)
                break
            }
            text.append(source, index, found.start)
            val offset = text.length
            val inner = source.substring(found.innerStart, found.innerEnd)
            when {
                // Bold, italic, strikethrough and a spoiler may hold each other, so what is
                // between the markers is read again.
                found.marker?.parsesInside == true -> {
                    val nested = parse(inner)
                    text.append(nested.text)
                    nested.spans.forEach { span -> spans += span.copy(offset = span.offset + offset) }
                }
                // A link is shown by the app as a link and nothing else: the format does not let
                // a bold run sit inside one. The markers are still taken out, so the label reads
                // as the words that were meant rather than as asterisks.
                found.marker == null -> text.append(parse(inner).text)
                else -> text.append(inner)
            }
            // The run covers what was written out, which is shorter than what was read once the
            // markers inside it are gone.
            spans += TextSpan(offset, text.length - offset, found.marker?.kind ?: TextKind.LINK, found.url)
            index = found.end
        }
        return FormattedText(text.toString(), spans.sortedWith(compareBy({ it.offset }, { -it.length })))
    }

    /** True when [source] carries at least one marker pair. */
    fun hasFormatting(source: String): Boolean = parse(source).hasFormatting

    /**
     * The first marker pair at or after [from], or null when the rest of [source] is plain text.
     *
     * A marker whose closer is missing is skipped and its first character becomes ordinary text,
     * so the scan continues past it instead of stopping there.
     */
    private fun findNext(source: String, from: Int): Found? {
        var index = from
        while (index < source.length) {
            if (source[index] == '[') {
                findLink(source, index)?.let { return it }
            }
            for (marker in MARKERS) {
                if (!source.startsWith(marker.token, index)) continue
                val closer = source.indexOf(marker.token, index + marker.token.length)
                if (closer > index + marker.token.length) {
                    return Found(
                        start = index,
                        end = closer + marker.token.length,
                        innerStart = index + marker.token.length,
                        innerEnd = closer,
                        marker = marker
                    )
                }
            }
            index++
        }
        return null
    }

    /** `[label](url)` at [start], or null when what is there is not a link. */
    private fun findLink(source: String, start: Int): Found? {
        val labelEnd = source.indexOf(']', start + 1)
        if (labelEnd <= start + 1) return null
        val urlStart = labelEnd + 2
        if (labelEnd + 1 >= source.length || source[labelEnd + 1] != '(') return null
        val urlEnd = source.indexOf(')', urlStart)
        if (urlEnd < 0) return null
        val url = source.substring(urlStart, urlEnd)
        if (!isUsableUrl(url)) return null
        return Found(
            start = start,
            end = urlEnd + 1,
            innerStart = start + 1,
            innerEnd = labelEnd,
            marker = null,
            url = url
        )
    }

    /**
     * True when [url] is something worth making a link.
     *
     * A space ends a link in every notation there is, so one here means the closing bracket was
     * part of the sentence and not of a link. An empty url is a typing accident, not a link.
     */
    private fun isUsableUrl(url: String): Boolean =
        url.isNotEmpty() && url.none { it.isWhitespace() } && !url.contains(')') && !url.contains('(')
}
