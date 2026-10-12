package com.telefarm.core.format

/** What the composer should hold after an edit, and where the caret belongs. */
data class ComposerEdit(
    val text: String,

    /** First character of the new selection. */
    val caretStart: Int,

    /** Last character of the new selection; equal to [caretStart] when nothing is selected. */
    val caretEnd: Int
)

/**
 * Putting formatting markers into a message while it is being written.
 *
 * The composer holds the same Markdown the parser reads, so a button here does what typing the
 * markers would do - and what typing them does depends on what is already under the caret. The
 * three cases have to be right every time, because getting one wrong means a button that either
 * will not work or eats the words it was pressed on:
 *
 * - nothing selected: the markers go in and the caret sits between them, ready for the words
 * - words selected: they are wrapped
 * - words already wrapped: the markers come back off, so the button is a toggle
 *
 * Indices are clamped rather than trusted. A caret position can be past the end of the text after
 * a word has been deleted underneath it, and a formatting button is the wrong place for that to
 * become a crash.
 */
object ComposerFormatting {

    /** Wraps or unwraps the selection with [marker] on both sides. */
    fun toggle(text: String, selectionStart: Int, selectionEnd: Int, marker: String): ComposerEdit {
        require(marker.isNotEmpty()) { "a formatting marker cannot be empty" }
        val length = text.length
        // Read the pair the right way round before clamping: a keyboard can hand back a selection
        // whose end is before its start, and clamping first would turn that into a caret.
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, length)
        val width = marker.length

        // The markers are already either side of the selection: take them off.
        if (start >= width && end + width <= length &&
            text.regionMatches(start - width, marker, 0, width) &&
            text.regionMatches(end, marker, 0, width)
        ) {
            val unwrapped = text.substring(0, start - width) +
                text.substring(start, end) +
                text.substring(end + width)
            return ComposerEdit(unwrapped, start - width, start - width + (end - start))
        }

        // The selection itself is a wrapped run, markers included. It is only taken apart when
        // the words inside carry no marker of their own: "**a** and **b**" selected whole is two
        // runs, not one, and unwrapping it would produce something nobody wrote.
        if (end - start > 2 * width &&
            text.regionMatches(start, marker, 0, width) &&
            text.regionMatches(end - width, marker, 0, width) &&
            !text.substring(start + width, end - width).contains(marker)
        ) {
            val unwrapped = text.substring(0, start) +
                text.substring(start + width, end - width) +
                text.substring(end)
            return ComposerEdit(unwrapped, start, end - 2 * width)
        }

        // Nothing selected: put the markers in and leave the caret between them.
        if (start == end) {
            val inserted = text.substring(0, start) + marker + marker + text.substring(end)
            return ComposerEdit(inserted, start + width, start + width)
        }

        // Words selected: wrap them, and keep them selected so another button can be pressed.
        val wrapped = text.substring(0, start) + marker + text.substring(start, end) +
            marker + text.substring(end)
        return ComposerEdit(wrapped, start + width, end + width)
    }

    /** `**bold**` */
    fun bold(text: String, start: Int, end: Int): ComposerEdit = toggle(text, start, end, "**")

    /** `__italic__` */
    fun italic(text: String, start: Int, end: Int): ComposerEdit = toggle(text, start, end, "__")

    /** `` `code` `` */
    fun code(text: String, start: Int, end: Int): ComposerEdit = toggle(text, start, end, "`")

    /** `~~struck through~~` */
    fun strikethrough(text: String, start: Int, end: Int): ComposerEdit = toggle(text, start, end, "~~")

    /** `||hidden until tapped||` */
    fun spoiler(text: String, start: Int, end: Int): ComposerEdit = toggle(text, start, end, "||")
}
