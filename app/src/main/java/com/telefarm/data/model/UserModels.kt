package com.telefarm.data.model

/** Presence of a user, as reported by Telegram. */
sealed interface UserStatusUi {
    data object Online : UserStatusUi
    data class Offline(val wasOnline: Int) : UserStatusUi
    data object Recently : UserStatusUi
    data object LastWeek : UserStatusUi
    data object LastMonth : UserStatusUi
    data object Hidden : UserStatusUi
    data object Empty : UserStatusUi
}

/** Presentation model of a Telegram user. */
data class UserUi(
    val id: Long,
    val displayName: String,
    val firstName: String,
    val lastName: String,
    val username: String?,
    val phoneNumber: String?,
    val photo: FileRef?,
    val status: UserStatusUi,
    val isBot: Boolean,
    val isVerified: Boolean,
    val isContact: Boolean,
    val isPremium: Boolean
)

/** One member of a group or channel. */
data class ChatMemberUi(
    val userId: Long,
    val displayName: String,
    val username: String?,
    val photo: FileRef?,
    val status: UserStatusUi,
    val roleLabelResId: Int?
)

/** Profile of a user or of a group/channel chat. */
data class ProfileUi(
    val id: Long,
    val isUser: Boolean,
    val title: String,
    val username: String?,
    val phoneNumber: String?,
    val bio: String?,
    val photo: FileRef?,
    val status: UserStatusUi?,
    val kind: ChatKind,
    val memberCount: Int,
    val canViewMembers: Boolean,
    val canSendMessage: Boolean,
    val canLeave: Boolean
)
