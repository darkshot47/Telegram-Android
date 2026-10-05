package com.telefarm.data.map

import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatMemberUi
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.FileRef
import com.telefarm.data.model.MessageContentUi
import com.telefarm.data.model.MessagePreview
import com.telefarm.data.model.MessageUi
import com.telefarm.data.model.PreviewKind
import com.telefarm.data.model.ProfileUi
import com.telefarm.data.model.SendState
import com.telefarm.data.model.StickerKind
import com.telefarm.data.model.UserStatusUi
import com.telefarm.data.model.UserUi
import org.drinkless.tdlib.TdApi

/**
 * Converts TDLib objects into the immutable models used by the rest of the application.
 *
 * The mapping is intentionally total: every TDLib content type ends up in one of the model
 * variants, and anything that cannot be rendered becomes an explicit "other" entry instead of
 * silently disappearing.
 */
object TdMappers {

    // region Files

    /** Maps a TDLib file. Returns null when there is no file to show. */
    fun fileRef(file: TdApi.File?): FileRef? {
        if (file == null || file.id == FileRef.NO_FILE) return null
        val local = file.local
        return FileRef(
            id = file.id,
            size = if (file.size > 0) file.size else file.expectedSize,
            localPath = local?.path?.takeIf { it.isNotEmpty() },
            isDownloaded = local?.isDownloadingCompleted == true,
            canBeDownloaded = local?.canBeDownloaded == true
        )
    }

    // endregion

    // region Chats

    /** Kind of a chat including Saved Messages. */
    fun chatKind(chat: TdApi.Chat, selfUserId: Long): ChatKind = when (val type = chat.type) {
        is TdApi.ChatTypePrivate ->
            if (selfUserId != 0L && type.userId == selfUserId) ChatKind.SAVED_MESSAGES else ChatKind.PRIVATE
        is TdApi.ChatTypeBasicGroup -> ChatKind.BASIC_GROUP
        is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChatKind.CHANNEL else ChatKind.SUPERGROUP
        is TdApi.ChatTypeSecret -> ChatKind.SECRET
        else -> ChatKind.UNKNOWN
    }

    /** Maps a chat for the chat list. */
    fun chat(
        chat: TdApi.Chat,
        selfUserId: Long,
        lastMessageSenderName: String? = null
    ): ChatUi {
        val kind = chatKind(chat, selfUserId)
        val lastMessage = chat.lastMessage
        val mainPosition = chat.positions?.firstOrNull { it.list is TdApi.ChatListMain }

        return ChatUi(
            id = chat.id,
            title = chat.title.orEmpty(),
            kind = kind,
            photo = fileRef(chat.photo?.small),
            lastMessagePreview = lastMessage?.let { preview(it) },
            lastMessageDate = lastMessage?.date ?: 0,
            lastMessageIsOutgoing = lastMessage?.isOutgoing ?: false,
            lastMessageIsEdited = (lastMessage?.editDate ?: 0) > 0,
            lastMessageSenderName = lastMessageSenderName,
            unreadCount = chat.unreadCount,
            unreadMentionCount = chat.unreadMentionCount,
            isMarkedAsUnread = chat.isMarkedAsUnread,
            isPinned = mainPosition?.isPinned == true,
            isMuted = (chat.notificationSettings?.muteFor ?: 0) > 0,
            canSendMessages = canSendMessages(chat, kind),
            draftText = draftText(chat),
            order = mainPosition?.order ?: Long.MIN_VALUE
        )
    }

    /** True when new messages may be written in this chat. */
    fun canSendMessages(chat: TdApi.Chat, kind: ChatKind): Boolean {
        val permissions = chat.permissions ?: return kind != ChatKind.CHANNEL
        val allowsText = permissions.canSendBasicMessages
        return when (kind) {
            ChatKind.CHANNEL -> false
            else -> allowsText
        }
    }

    private fun draftText(chat: TdApi.Chat): String? {
        val content = chat.draftMessage?.content
        return if (content is TdApi.DraftMessageContentText) content.text?.text else null
    }

    /** Last message preview that the UI can render in the chat list. */
    fun preview(message: TdApi.Message): MessagePreview = when (val content = message.content) {
        is TdApi.MessageText -> MessagePreview.Text(content.text?.text.orEmpty())
        is TdApi.MessagePhoto -> MessagePreview.Kind(PreviewKind.PHOTO)
        is TdApi.MessageVideo -> MessagePreview.Kind(PreviewKind.VIDEO)
        is TdApi.MessageAnimation -> MessagePreview.Kind(PreviewKind.ANIMATION)
        is TdApi.MessageDocument -> MessagePreview.Kind(PreviewKind.DOCUMENT)
        is TdApi.MessageVoiceNote -> MessagePreview.Kind(PreviewKind.VOICE)
        is TdApi.MessageAudio -> MessagePreview.Kind(PreviewKind.AUDIO)
        is TdApi.MessageSticker, is TdApi.MessageAnimatedEmoji -> MessagePreview.Kind(PreviewKind.STICKER)
        is TdApi.MessageVideoNote -> MessagePreview.Kind(PreviewKind.VIDEO_NOTE)
        is TdApi.MessageLocation, is TdApi.MessageVenue -> MessagePreview.Kind(PreviewKind.LOCATION)
        is TdApi.MessageContact -> MessagePreview.Kind(PreviewKind.CONTACT)
        is TdApi.MessagePoll -> MessagePreview.Kind(PreviewKind.POLL)
        is TdApi.MessageCall -> MessagePreview.Kind(PreviewKind.CALL)
        is TdApi.MessageGame, is TdApi.MessageGameScore -> MessagePreview.Kind(PreviewKind.UNSUPPORTED)
        is TdApi.MessageExpiredPhoto, is TdApi.MessageExpiredVideo,
        is TdApi.MessageExpiredVoiceNote, is TdApi.MessageExpiredVideoNote ->
            MessagePreview.Kind(PreviewKind.UNSUPPORTED)
        else -> MessagePreview.Kind(PreviewKind.SERVICE)
    }

    // endregion

    // region Messages

    /** Maps a message for the conversation screen. */
    fun message(
        message: TdApi.Message,
        selfUserId: Long,
        chatKind: ChatKind,
        lastReadOutboxMessageId: Long,
        senderDisplayName: String?
    ): MessageUi {
        val senderId = message.senderId.userIdOrNull()
        val showSender = chatKind.isGroup && !message.isOutgoing && senderId != selfUserId
        val sendingState = message.sendingState
        val failed = sendingState as? TdApi.MessageSendingStateFailed
        return MessageUi(
            id = message.id,
            chatId = message.chatId,
            senderId = senderId,
            senderName = senderDisplayName,
            showSender = showSender && !senderDisplayName.isNullOrBlank(),
            isOutgoing = message.isOutgoing,
            date = message.date,
            isEdited = message.editDate > 0,
            sendState = when {
                failed != null -> SendState.FAILED
                sendingState is TdApi.MessageSendingStatePending -> SendState.SENDING
                else -> SendState.SENT
            },
            canRetry = failed?.canRetry == true,
            isRead = !message.isOutgoing || message.id <= lastReadOutboxMessageId,
            content = content(message.content)
        )
    }

    /** Maps message content that has a dedicated view. */
    fun content(content: TdApi.MessageContent): MessageContentUi = when (content) {
        is TdApi.MessageText -> MessageContentUi.Text(content.text?.text.orEmpty())

        is TdApi.MessagePhoto -> MessageContentUi.Photo(
            preview = smallestUsable(content.photo)?.let { fileRef(it) },
            full = fileRef(content.photo?.sizes?.lastOrNull { it.type.isNotBlank() }?.photo),
            caption = content.caption?.text?.takeIf { it.isNotBlank() }
        )

        is TdApi.MessageVideo -> MessageContentUi.Video(
            thumbnail = fileRef(content.video?.thumbnail?.file),
            file = fileRef(content.video?.video),
            caption = content.caption?.text?.takeIf { it.isNotBlank() },
            duration = content.video?.duration ?: 0,
            fileName = content.video?.fileName?.takeIf { it.isNotBlank() }
        )

        is TdApi.MessageVideoNote -> MessageContentUi.Other(PreviewKind.VIDEO_NOTE)

        is TdApi.MessageAnimation -> MessageContentUi.Other(PreviewKind.ANIMATION)

        is TdApi.MessageDocument -> MessageContentUi.Document(
            file = fileRef(content.document?.document),
            fileName = content.document?.fileName?.takeIf { it.isNotBlank() }
                ?: DEFAULT_DOCUMENT_NAME,
            mimeType = content.document?.mimeType?.takeIf { it.isNotBlank() },
            size = content.document?.document?.size ?: 0L,
            caption = content.caption?.text?.takeIf { it.isNotBlank() }
        )

        is TdApi.MessageVoiceNote -> MessageContentUi.Voice(
            file = fileRef(content.voiceNote?.voice),
            duration = content.voiceNote?.duration ?: 0
        )

        is TdApi.MessageAudio -> MessageContentUi.Audio(
            file = fileRef(content.audio?.audio),
            title = content.audio?.title?.describeIfNotEmpty(),
            performer = content.audio?.performer?.describeIfNotEmpty(),
            duration = content.audio?.duration ?: 0
        )

        is TdApi.MessageSticker -> MessageContentUi.Sticker(
            file = fileRef(stickerFile(content.sticker, content.sticker?.format)),
            kind = stickerKind(content.sticker)
        )

        is TdApi.MessageAnimatedEmoji -> MessageContentUi.Sticker(
            file = fileRef(stickerFile(content.animatedEmoji?.sticker, content.animatedEmoji?.sticker?.format)),
            kind = stickerKind(content.animatedEmoji?.sticker)
        )

        else -> MessageContentUi.Other(unrenderedKind(content))
    }

    /** File of a sticker: the standalone file for static and video formats, the thumbnail for Lottie. */
    private fun stickerFile(sticker: TdApi.Sticker?, format: TdApi.StickerFormat?): TdApi.File? {
        return when (format) {
            is TdApi.StickerFormatTgs -> sticker?.thumbnail?.file
            else -> sticker?.sticker
        }
    }

    private fun stickerKind(sticker: TdApi.Sticker?): StickerKind = when (sticker?.format) {
        is TdApi.StickerFormatTgs -> StickerKind.ANIMATED
        is TdApi.StickerFormatWebm -> StickerKind.VIDEO
        else -> StickerKind.STATIC
    }

    private fun unrenderedKind(content: TdApi.MessageContent): PreviewKind = when (content) {
        is TdApi.MessageLocation, is TdApi.MessageVenue -> PreviewKind.LOCATION
        is TdApi.MessageContact -> PreviewKind.CONTACT
        is TdApi.MessagePoll -> PreviewKind.POLL
        is TdApi.MessageCall -> PreviewKind.CALL
        is TdApi.MessageExpiredPhoto -> PreviewKind.PHOTO
        is TdApi.MessageExpiredVideo -> PreviewKind.VIDEO
        is TdApi.MessageExpiredVoiceNote -> PreviewKind.VOICE
        is TdApi.MessageExpiredVideoNote -> PreviewKind.VIDEO_NOTE
        is TdApi.MessageGame, is TdApi.MessageGameScore -> PreviewKind.UNSUPPORTED
        is TdApi.MessageUnsupported -> PreviewKind.UNSUPPORTED
        else -> PreviewKind.SERVICE
    }

    /** Smallest photo size that is big enough to be displayed, preferring medium sizes. */
    private fun smallestUsable(photo: TdApi.Photo?): TdApi.File? {
        val sizes = photo?.sizes ?: return null
        val preferred = sizes.firstOrNull { it.type == "m" }
            ?: sizes.firstOrNull { it.type == "x" }
            ?: sizes.firstOrNull { it.type == "s" }
            ?: sizes.lastOrNull()
        return preferred?.photo
    }

    // endregion

    // region Users

    /** User display name with a fallback for empty profiles. */
    fun displayName(user: TdApi.User?): String {
        if (user == null) return ""
        val name = listOfNotNull(
            user.firstName?.takeIf { it.isNotBlank() },
            user.lastName?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        return name.ifBlank { user.usernames?.editableUsername?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty() }
    }

    /** Active username of a user, without the leading at sign. */
    fun username(user: TdApi.User?): String? =
        user?.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** Presence of a user. */
    fun status(status: TdApi.UserStatus?): UserStatusUi = when (status) {
        is TdApi.UserStatusOnline -> UserStatusUi.Online
        is TdApi.UserStatusOffline -> UserStatusUi.Offline(status.wasOnline)
        is TdApi.UserStatusRecently -> UserStatusUi.Recently
        is TdApi.UserStatusLastWeek -> UserStatusUi.LastWeek
        is TdApi.UserStatusLastMonth -> UserStatusUi.LastMonth
        is TdApi.UserStatusEmpty -> UserStatusUi.Empty
        else -> UserStatusUi.Hidden
    }

    /** Maps a user for lists and profiles. */
    fun user(user: TdApi.User): UserUi = UserUi(
        id = user.id,
        displayName = displayName(user),
        firstName = user.firstName.orEmpty(),
        lastName = user.lastName.orEmpty(),
        username = username(user),
        phoneNumber = user.phoneNumber?.takeIf { it.isNotBlank() },
        photo = fileRef(user.profilePhoto?.small),
        status = status(user.status),
        isBot = user.type is TdApi.UserTypeBot,
        isVerified = user.verificationStatus?.isVerified == true,
        isContact = user.isContact,
        isPremium = user.isPremium
    )

    /** Maps one member of a group or channel. */
    fun chatMember(member: TdApi.ChatMember, user: TdApi.User?): ChatMemberUi = ChatMemberUi(
        userId = member.memberId?.userIdOrNull() ?: 0L,
        displayName = displayName(user),
        username = username(user),
        photo = fileRef(user?.profilePhoto?.small),
        status = status(user?.status),
        roleLabelResId = memberRoleResId(member.status)
    )

    /** Resource id of the role label of a member, or null for ordinary members. */
    private fun memberRoleResId(status: TdApi.ChatMemberStatus?): Int? = when (status) {
        is TdApi.ChatMemberStatusCreator -> com.telefarm.R.string.member_role_owner
        is TdApi.ChatMemberStatusAdministrator -> com.telefarm.R.string.member_role_admin
        is TdApi.ChatMemberStatusRestricted -> com.telefarm.R.string.member_role_restricted
        is TdApi.ChatMemberStatusBanned -> com.telefarm.R.string.member_role_banned
        else -> null
    }

    /** Profile of a group or channel chat. */
    fun profile(
        chat: TdApi.Chat,
        selfUserId: Long,
        bio: String?,
        memberCount: Int,
        canViewMembers: Boolean,
        canLeave: Boolean
    ): ProfileUi {
        val kind = chatKind(chat, selfUserId)
        return ProfileUi(
            id = chat.id,
            isUser = false,
            title = chat.title.orEmpty(),
            username = null,
            phoneNumber = null,
            bio = bio?.takeIf { it.isNotBlank() },
            photo = fileRef(chat.photo?.big ?: chat.photo?.small),
            status = null,
            kind = kind,
            memberCount = memberCount,
            canViewMembers = canViewMembers,
            canSendMessage = canSendMessages(chat, kind) && kind != ChatKind.CHANNEL,
            canLeave = canLeave
        )
    }

    /** Last message sender, used for the "who wrote this" prefix in the chat list. */
    fun lastMessageSenderId(message: TdApi.Message, selfUserId: Long): Long? {
        if (message.isOutgoing) return selfUserId
        return message.senderId.userIdOrNull()
    }

    private const val DEFAULT_DOCUMENT_NAME = "file"
}

/** User id behind a message sender, or null for chats and anonymous senders. */
fun TdApi.MessageSender?.userIdOrNull(): Long? = when (this) {
    is TdApi.MessageSenderUser -> userId
    else -> null
}

/** Keeps an optional TDLib string only when it carries information. */
private fun String?.describeIfNotEmpty(): String? = this?.takeIf { it.isNotBlank() }
