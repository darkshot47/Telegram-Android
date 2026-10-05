package com.telefarm.data.model

/** Kind of a chat, derived from the TDLib chat type. */
enum class ChatKind {
    PRIVATE,
    SAVED_MESSAGES,
    BASIC_GROUP,
    SUPERGROUP,
    CHANNEL,
    SECRET,
    UNKNOWN;

    val isGroup: Boolean get() = this == BASIC_GROUP || this == SUPERGROUP
    val isChannel: Boolean get() = this == CHANNEL
}

/** Content kinds that appear in the chat list without their body. */
enum class PreviewKind {
    PHOTO, VIDEO, DOCUMENT, VOICE, AUDIO, STICKER, VIDEO_NOTE, ANIMATION,
    LOCATION, CONTACT, POLL, CALL, SERVICE, UNSUPPORTED
}

/** Last message of a chat, in a form the UI can render without touching TDLib. */
sealed interface MessagePreview {
    data class Text(val text: String) : MessagePreview
    data class Kind(val kind: PreviewKind) : MessagePreview
}

/** Everything the chat list needs to render one row. */
data class ChatUi(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val photo: FileRef?,
    val lastMessagePreview: MessagePreview?,
    val lastMessageDate: Int,
    val lastMessageIsOutgoing: Boolean,
    val lastMessageIsEdited: Boolean,
    val lastMessageSenderName: String?,
    val unreadCount: Int,
    val unreadMentionCount: Int,
    val isMarkedAsUnread: Boolean,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val canSendMessages: Boolean,
    val draftText: String?,
    val order: Long,
    val hasUnread: Boolean = unreadCount > 0 || unreadMentionCount > 0 || isMarkedAsUnread
)

/** Extra information about a group or channel, loaded on demand. */
data class ChatDetails(
    val description: String?,
    val memberCount: Int,
    val canViewMembers: Boolean
)

/** Action another member performs in the open conversation. */
enum class ChatActionUi {
    TYPING,
    UPLOADING_PHOTO,
    RECORDING_VOICE,
    UPLOADING_DOCUMENT,
    CHOOSING_STICKER,
    RECORDING_VIDEO,
    PLAYING_GAME
}

/** Secondary line of the conversation header. */
sealed interface ChatSubtitle {
    data class Status(val status: UserStatusUi) : ChatSubtitle
    data class Members(val count: Int, val isChannel: Boolean) : ChatSubtitle
    data object Self : ChatSubtitle
}

/** Title area of the conversation screen. */
data class ChatHeaderUi(
    val title: String,
    val subtitle: ChatSubtitle?,
    val canSendMessages: Boolean,
    val photo: FileRef?,
    val kind: ChatKind
) {
    companion object {
        /** Placeholder used before the chat is loaded. */
        val EMPTY = ChatHeaderUi("", null, false, null, ChatKind.UNKNOWN)
    }
}

/** Connection state of the TDLib client, shown as a banner. */
enum class ConnectionStatus {
    READY,
    UPDATING,
    CONNECTING,
    WAITING_FOR_NETWORK;

    val isOffline: Boolean get() = this == WAITING_FOR_NETWORK
}

/** Loading state of the chat list screen. */
sealed interface ChatListState {
    data object Loading : ChatListState
    data class Content(val chats: List<ChatUi>) : ChatListState
    data class Empty(val message: UiMessage?) : ChatListState
    data class Failed(val message: UiMessage) : ChatListState
}
