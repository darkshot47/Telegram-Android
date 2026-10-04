package com.telefarm.ui.common

import android.content.Context
import com.telefarm.R
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.MessagePreview
import com.telefarm.data.model.PreviewKind

/**
 * Text of the second line of a chat list row.
 *
 * Shared by the chat list and by search results so both screens describe a conversation in
 * exactly the same way.
 */
object PreviewText {

    /** Preview line for a chat: draft, outgoing prefix, sender name or content placeholder. */
    fun of(context: Context, chat: ChatUi): String {
        chat.draftText?.let { draft ->
            return context.getString(R.string.chat_draft_prefix, draft)
        }
        val preview = chat.lastMessagePreview ?: return ""
        val body = when (preview) {
            is MessagePreview.Text -> preview.text
            is MessagePreview.Kind -> of(context, preview.kind)
            MessagePreview.Service -> context.getString(R.string.message_service)
        }
        if (body.isEmpty()) return ""
        return when {
            chat.lastMessageIsOutgoing -> context.getString(R.string.chat_you_prefix, body)
            !chat.lastMessageSenderName.isNullOrBlank() -> "${chat.lastMessageSenderName}: $body"
            else -> body
        }
    }

    /** Placeholder for chats whose last message is not text. */
    fun of(context: Context, kind: PreviewKind): String = context.getString(
        when (kind) {
            PreviewKind.PHOTO -> R.string.message_photo
            PreviewKind.VIDEO -> R.string.message_video
            PreviewKind.DOCUMENT -> R.string.message_document
            PreviewKind.VOICE -> R.string.message_voice
            PreviewKind.AUDIO -> R.string.message_audio
            PreviewKind.STICKER -> R.string.message_sticker
            PreviewKind.VIDEO_NOTE -> R.string.message_video_note
            PreviewKind.ANIMATION -> R.string.message_animation
            PreviewKind.LOCATION -> R.string.message_location
            PreviewKind.CONTACT -> R.string.message_contact
            PreviewKind.POLL -> R.string.message_poll
            PreviewKind.CALL -> R.string.message_call
            PreviewKind.GAME, PreviewKind.UNSUPPORTED -> R.string.message_unsupported
        }
    )
}
