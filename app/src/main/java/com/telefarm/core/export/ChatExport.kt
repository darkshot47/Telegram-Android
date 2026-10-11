package com.telefarm.core.export

import com.telefarm.core.time.CivilDate
import com.telefarm.data.model.MessageContentUi
import com.telefarm.data.model.MessageUi
import com.telefarm.data.model.PreviewKind
import com.telefarm.data.model.StickerKind

/** How the transcript is written. */
enum class ExportFormat(

    /** Extension of the file. */
    val extension: String,

    /** What to tell the application the file is shared with. */
    val mimeType: String
) {

    /** Plain text: one line per message, readable anywhere. */
    TEXT("txt", "text/plain"),

    /** A page that opens in a browser and needs nothing else. */
    HTML("html", "text/html")
}

/** What goes into a transcript. */
data class ExportOptions(

    val format: ExportFormat = ExportFormat.HTML,

    /** Names in front of messages, which only matters where more than one person writes. */
    val includeSenders: Boolean = true,

    /** A heading above each day. */
    val includeDates: Boolean = true,

    /** Placeholders for photos, files and voice notes. Turning it off exports words only. */
    val includeMedia: Boolean = true,

    /** Oldest message to include, in seconds since the epoch. */
    val fromSeconds: Long = Long.MIN_VALUE,

    /** Newest message to include, in seconds since the epoch. */
    val toSeconds: Long = Long.MAX_VALUE,

    /** Zone the dates are written in. */
    val utcOffsetMinutes: Int = 0
)

/**
 * Writes a conversation out as a document.
 *
 * A transcript leaves the app, so it is treated like anything else that does: every character of
 * what people wrote is escaped before it goes near markup, a file name is stripped of everything
 * a file system would object to, and nothing about the account - no id, no phone number - is put
 * in it that the conversation itself does not already carry.
 *
 * Media is written as a placeholder, because the bytes of a photo are not part of a transcript
 * and copying them out would mean copying them somewhere they can be lost. The caption is kept:
 * that is words somebody wrote.
 */
object ChatExport {

    /** Longest file name, before the extension. */
    const val MAX_FILE_NAME_LENGTH = 60

    /** [messages] as a document. */
    fun export(chatTitle: String, messages: List<MessageUi>, options: ExportOptions): String =
        when (options.format) {
            ExportFormat.TEXT -> exportText(chatTitle, messages, options)
            ExportFormat.HTML -> exportHtml(chatTitle, messages, options)
        }

    /** A safe name for the file: `chat title.html`. */
    fun fileName(chatTitle: String, options: ExportOptions): String =
        "${sanitiseFileName(chatTitle)}.${options.format.extension}"

    /**
     * A title that can be used as a file name.
     *
     * Path separators, control characters and the characters a shell or a file system treats
     * specially are replaced with a space, runs of spaces are collapsed, and an empty result
     * falls back to a word rather than to a file with no name.
     */
    fun sanitiseFileName(title: String): String {
        val cleaned = title.map { character ->
            when {
                character == '/' || character == '\\' -> ' '
                character.code < 0x20 -> ' '
                character in RESERVED_CHARACTERS -> ' '
                else -> character
            }
        }.joinToString("").trim().replace(WHITESPACE_RUN, " ")
        val shortened = if (cleaned.length > MAX_FILE_NAME_LENGTH) {
            cleaned.substring(0, MAX_FILE_NAME_LENGTH).trim()
        } else {
            cleaned
        }
        return shortened.ifEmpty { FALLBACK_FILE_NAME }
    }

    // region Plain text

    private fun exportText(chatTitle: String, messages: List<MessageUi>, options: ExportOptions): String {
        val kept = selected(messages, options)
        val out = StringBuilder()
        var written = 0
        out.appendLine(chatTitle)
        out.appendLine("=".repeat(chatTitle.length.coerceAtLeast(1)))
        out.appendLine()
        var lastDay: Long? = null
        var lastSender: String? = null
        for (message in kept) {
            val body = bodyOf(message.content, options)
            if (body == null) continue
            val when_ = CivilDate.fromEpochSeconds(message.date.toLong(), options.utcOffsetMinutes)
            if (options.includeDates && when_.epochDay != lastDay) {
                lastDay = when_.epochDay
                lastSender = null
                out.appendLine()
                out.appendLine(when_.longDate)
                out.appendLine("-".repeat(when_.longDate.length))
            }
            if (options.includeSenders) {
                val sender = senderOf(message)
                if (sender != lastSender) {
                    lastSender = sender
                }
                out.append("[${when_.clock}] $sender: ")
            } else {
                out.append("[${when_.clock}] ")
            }
            written++
            out.appendLine(body.replace("\n", "\n    "))
            if (message.isEdited) out.appendLine("    (edited)")
        }
        out.appendLine()
        out.appendLine("$written ${plural(written)}")
        return out.toString()
    }

    // endregion

    // region HTML

    private fun exportHtml(chatTitle: String, messages: List<MessageUi>, options: ExportOptions): String {
        val kept = selected(messages, options)
        val out = StringBuilder()
        var written = 0
        out.appendLine("<!DOCTYPE html>")
        out.appendLine("<html lang=\"en\">")
        out.appendLine("<head>")
        out.appendLine("<meta charset=\"utf-8\">")
        out.appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        out.appendLine("<title>${escapeHtml(chatTitle)}</title>")
        out.appendLine(STYLE)
        out.appendLine("</head>")
        out.appendLine("<body>")
        out.appendLine("<h1>${escapeHtml(chatTitle)}</h1>")
        out.appendLine("<p class=\"meta\">$written ${plural(written)}</p>")
        var lastDay: Long? = null
        var lastSender: String? = null
        for (message in kept) {
            val body = bodyOf(message.content, options)
            if (body == null) continue
            val when_ = CivilDate.fromEpochSeconds(message.date.toLong(), options.utcOffsetMinutes)
            if (options.includeDates && when_.epochDay != lastDay) {
                lastDay = when_.epochDay
                lastSender = null
                out.appendLine("<h2>${escapeHtml(when_.longDate)}</h2>")
            }
            val direction = if (message.isOutgoing) "out" else "in"
            out.appendLine("<div class=\"msg $direction\">")
            if (options.includeSenders) {
                val sender = senderOf(message)
                if (sender != lastSender) {
                    lastSender = sender
                    out.appendLine("<div class=\"who\">${escapeHtml(sender)}</div>")
                }
            }
            out.appendLine("<div class=\"text\">${escapeHtml(body).replace("\n", "<br>")}</div>")
            written++
            out.appendLine("<div class=\"time\">${when_.clock}${if (message.isEdited) " · edited" else ""}</div>")
            out.appendLine("</div>")
        }
        out.appendLine("</body>")
        out.appendLine("</html>")
        return out.toString()
    }

    /**
     * Everything that makes a character able to leave the text it was typed into.
     *
     * A message can contain `<script>`, and it is somebody's message, not code. Escaping is what
     * keeps a transcript a document.
     */
    fun escapeHtml(text: String): String = buildString(text.length) {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    private val STYLE = """
<style>
  body { font: 16px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 46rem; padding: 2rem 1rem; color: #17181c; }
  h1 { font-size: 1.5rem; margin: 0 0 .25rem; }
  h2 { font-size: 1rem; margin: 2rem 0 .75rem; color: #5b6070; font-weight: 600; }
  .meta { color: #5b6070; margin: 0 0 1.5rem; }
  .msg { border-left: 3px solid #c9ccd6; padding: .35rem 0 .35rem .75rem; margin: .5rem 0; }
  .msg.out { border-left-color: #2f7cf6; }
  .who { font-weight: 600; }
  .time { color: #8a8fa0; font-size: .8rem; }
</style>""".trimIndent()

    // endregion

    // region Shared

    /** `message` or `messages`, so the summary does not say "1 messages". */
    private fun plural(count: Int): String = if (count == 1) "message" else "messages"

    /** The messages the options ask for, in the order they were written. */
    private fun selected(messages: List<MessageUi>, options: ExportOptions): List<MessageUi> =
        messages.filter { it.date.toLong() in options.fromSeconds..options.toSeconds }
            .sortedBy { it.date }

    /** Who wrote [message], in the words the conversation already uses. */
    private fun senderOf(message: MessageUi): String = when {
        message.isOutgoing -> "You"
        !message.senderName.isNullOrBlank() -> message.senderName
        else -> "Unknown"
    }

    /**
     * What to write for a message, or null when there is nothing to write.
     *
     * Null keeps a message out of the transcript entirely, which is what turning media off does
     * to a message that was only a photo: a line saying `[Photo]` with nothing after it is noise.
     */
    private fun bodyOf(content: MessageContentUi, options: ExportOptions): String? {
        val caption: String?
        val placeholder: String?
        when (content) {
            is MessageContentUi.Text -> return content.text.ifBlank { null }
            is MessageContentUi.Photo -> {
                caption = content.caption
                placeholder = "[Photo]"
            }
            is MessageContentUi.Video -> {
                caption = content.caption
                placeholder = "[Video ${duration(content.duration)}]"
            }
            is MessageContentUi.Document -> {
                caption = content.caption
                placeholder = "[File ${content.fileName}]"
            }
            is MessageContentUi.Voice -> {
                caption = null
                placeholder = "[Voice message ${duration(content.duration)}]"
            }
            is MessageContentUi.Audio -> {
                caption = null
                placeholder = listOfNotNull(content.performer, content.title)
                    .joinToString(" - ")
                    .ifEmpty { "Audio" }
                    .let { "[Audio $it ${duration(content.duration)}]" }
            }
            is MessageContentUi.Sticker -> {
                caption = null
                placeholder = when (content.kind) {
                    StickerKind.STATIC -> "[Sticker]"
                    StickerKind.ANIMATED -> "[Animated sticker]"
                    StickerKind.VIDEO -> "[Video sticker]"
                }
            }
            is MessageContentUi.Other -> {
                caption = null
                placeholder = "[${labelOf(content.kind)}]"
            }
        }
        val words = caption?.takeIf { it.isNotBlank() }
        return when {
            words != null && options.includeMedia -> "$placeholder\n$words"
            words != null -> words
            options.includeMedia -> placeholder
            else -> null
        }
    }

    /** A clock reading for a length: `0:12`, `1:04:03`. */
    private fun duration(seconds: Int): String {
        val bounded = seconds.coerceAtLeast(0)
        val hours = bounded / 3600
        val minutes = (bounded % 3600) / 60
        val rest = bounded % 60
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        } else {
            "$minutes:${rest.toString().padStart(2, '0')}"
        }
    }

    private fun labelOf(kind: PreviewKind): String = when (kind) {
        PreviewKind.PHOTO -> "Photo"
        PreviewKind.VIDEO -> "Video"
        PreviewKind.DOCUMENT -> "File"
        PreviewKind.VOICE -> "Voice message"
        PreviewKind.AUDIO -> "Audio"
        PreviewKind.STICKER -> "Sticker"
        PreviewKind.VIDEO_NOTE -> "Video message"
        PreviewKind.ANIMATION -> "Animation"
        PreviewKind.LOCATION -> "Location"
        PreviewKind.CONTACT -> "Contact"
        PreviewKind.POLL -> "Poll"
        PreviewKind.CALL -> "Call"
        PreviewKind.SERVICE -> "Service message"
        PreviewKind.UNSUPPORTED -> "Unsupported message"
    }

    // endregion

    private val WHITESPACE_RUN = Regex("\\s+")

    /** Characters a file system or a shell treats specially. */
    private const val RESERVED_CHARACTERS = "<>:\"|?*"

    private const val FALLBACK_FILE_NAME = "chat"
}
