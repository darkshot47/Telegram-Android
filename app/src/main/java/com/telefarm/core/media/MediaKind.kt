package com.telefarm.core.media

/** What a picked file is, which decides how it is sent. */
enum class MediaKind {

    /** Sent as a photo, shown as a picture. */
    PHOTO,

    /** Sent as an animation, so it moves. */
    ANIMATION,

    /** Sent as a video. */
    VIDEO,

    /** Sent as audio, with a player. */
    AUDIO,

    /** Sent as a file, with its name. */
    DOCUMENT
}

/**
 * Working out what a picked file is, and what to call the copy of it.
 *
 * A file picker hands back a MIME type and sometimes a name, and neither can be trusted on its
 * own: the type is what the app that saved the file claimed, and the name may have no extension
 * at all, or one that does not match. The type is believed first because it is what the sending
 * side will act on, and the extension is the fallback rather than the other way round.
 *
 * Getting this wrong is visible: a photo sent as a document arrives as a thing to download instead
 * of a picture, and an animation sent as a photo stops moving.
 *
 * The copy is named here rather than at the call site because the name is written into a shared
 * directory. A picked file is named by whatever saved it, so the name has to be taken apart before
 * it is used as a path - `../` in a file name is not a typo, it is a way out of the directory.
 */
object MediaFiles {

    /** The longest file name kept, so a long one cannot overrun a path. */
    const val MAX_NAME_LENGTH = 80

    /**
     * How big a picture can be and still be sent as one.
     *
     * A photo is capped at ten megabytes; past that it has to travel as a file. Sending it as a
     * photo anyway would simply fail, and a picture that arrives as a file is a smaller
     * disappointment than one that does not arrive.
     */
    const val MAX_PHOTO_BYTES = 10L * 1_024 * 1_024

    private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
    private val ANIMATION_EXTENSIONS = setOf("gif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "ogg", "oga", "wav", "flac", "aac", "opus")

    /**
     * What the file is.
     *
     * @param mimeType what the picker reported, null when it reported nothing.
     * @param fileName the name it came with, null when it has none.
     */
    fun of(mimeType: String?, fileName: String?): MediaKind {
        val type = mimeType?.trim()?.lowercase().orEmpty()
        if (type.isNotEmpty() && type != "application/octet-stream") {
            // An animation claims to be an image, and is the one case where the more specific
            // answer has to win over the type's prefix.
            if (type == "image/gif") return MediaKind.ANIMATION
            return when {
                type.startsWith("image/") -> MediaKind.PHOTO
                type.startsWith("video/") -> MediaKind.VIDEO
                type.startsWith("audio/") -> MediaKind.AUDIO
                else -> MediaKind.DOCUMENT
            }
        }
        return when (extensionOf(fileName)) {
            in PHOTO_EXTENSIONS -> MediaKind.PHOTO
            in ANIMATION_EXTENSIONS -> MediaKind.ANIMATION
            in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            else -> MediaKind.DOCUMENT
        }
    }

    /**
     * What the file will actually be sent as, once its size is known.
     *
     * Only a picture changes: one too big to be a photo goes as a file rather than not going at
     * all. Everything else is sent the way it was recognised, whatever its size.
     */
    fun sendAs(kind: MediaKind, sizeBytes: Long): MediaKind =
        if (kind == MediaKind.PHOTO && sizeBytes > MAX_PHOTO_BYTES) MediaKind.DOCUMENT else kind

    /** The lower-case extension of a file name, or an empty string when it has none. */
    fun extensionOf(fileName: String?): String {
        val name = fileName.orEmpty()
        val dot = name.lastIndexOf('.')
        // A leading dot is a hidden file's whole name, not an extension: ".gitignore" has none.
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * A name for the copy, safe to put in a directory.
     *
     * The name is rebuilt rather than cleaned: only letters, digits, a few marks and one dot
     * survive, so nothing that means "elsewhere" can survive with it. Two files picked in a row
     * are told apart by [unique], because a picker will happily hand back the same name twice.
     */
    fun cacheFileName(fileName: String?, mimeType: String?, unique: Long): String {
        val original = fileName.orEmpty().substringAfterLast('/')
        val extension = extensionOf(original).ifEmpty { extensionForType(mimeType) }
        // A name with no dot is all stem; substringBeforeLast would return nothing for it and
        // turn a file called "video" into a file called "file".
        val stem = (if ('.' in original) original.substringBeforeLast('.') else original)
            .map { if (isAllowed(it)) it else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_NAME_LENGTH)
            .trim()
            .ifEmpty { "file" }
        return if (extension.isEmpty()) "$stem-$unique" else "$stem-$unique.$extension"
    }

    /**
     * Whether a character is kept in a name.
     *
     * Letters and digits of any script, and the combining marks that go with them. Hindi is
     * written with marks that are not letters - the `ि` in `रिपोर्ट` is one - and dropping them
     * would turn a name a person recognises into one they do not.
     */
    private fun isAllowed(character: Char): Boolean = character.isLetterOrDigit() ||
        character == '-' || character == '_' ||
        character.category == CharCategory.NON_SPACING_MARK ||
        character.category == CharCategory.COMBINING_SPACING_MARK

    /** The extension a MIME type implies, for a file that arrived without a name. */
    private fun extensionForType(mimeType: String?): String = when (mimeType?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "audio/mpeg" -> "mp3"
        "audio/ogg" -> "ogg"
        "application/pdf" -> "pdf"
        else -> ""
    }
}
