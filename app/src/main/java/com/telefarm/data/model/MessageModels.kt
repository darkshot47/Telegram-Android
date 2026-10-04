package com.telefarm.data.model

/** Delivery state of an outgoing message. */
enum class SendState { SENT, SENDING, FAILED }

/**
 * Presentation model of message content.
 *
 * Content that Telefarm can render gets its own type; everything else is reported as [Other]
 * with a label, so a message is never shown as something it is not.
 */
sealed interface MessageContentUi {

    data class Text(val text: String) : MessageContentUi

    data class Photo(
        val preview: FileRef?,
        val full: FileRef?,
        val caption: String?
    ) : MessageContentUi

    data class Video(
        val thumbnail: FileRef?,
        val file: FileRef?,
        val caption: String?,
        val duration: Int,
        val fileName: String?
    ) : MessageContentUi

    data class Document(
        val file: FileRef?,
        val fileName: String,
        val mimeType: String?,
        val size: Long,
        val caption: String?
    ) : MessageContentUi

    data class Voice(
        val file: FileRef?,
        val duration: Int
    ) : MessageContentUi

    data class Audio(
        val file: FileRef?,
        val title: String?,
        val performer: String?,
        val duration: Int
    ) : MessageContentUi

    data class Sticker(
        val file: FileRef?,
        val kind: StickerKind
    ) : MessageContentUi

    /** Service messages and content that is not rendered yet. */
    data class Other(val kind: PreviewKind) : MessageContentUi
}

/** Format of a sticker file. */
enum class StickerKind { STATIC, ANIMATED, VIDEO }

/** One logical message of a conversation. */
data class MessageUi(
    val id: Long,
    val chatId: Long,
    val senderId: Long?,
    val senderName: String?,
    val showSender: Boolean,
    val isOutgoing: Boolean,
    val date: Int,
    val isEdited: Boolean,
    val sendState: SendState,
    val canRetry: Boolean,
    val isRead: Boolean,
    val content: MessageContentUi
)

/** Rows rendered by the conversation list. */
sealed interface MessageRow {
    val stableId: Long

    data class Message(val message: MessageUi) : MessageRow {
        override val stableId: Long get() = message.id
    }

    data class DateSeparator(val date: Int) : MessageRow {
        override val stableId: Long get() = date.toLong() * 31L + 7L
    }

    data class LoadMore(val isLoading: Boolean) : MessageRow {
        override val stableId: Long get() = -1L
    }
}
