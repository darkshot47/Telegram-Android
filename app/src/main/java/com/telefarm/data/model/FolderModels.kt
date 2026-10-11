package com.telefarm.data.model

/**
 * A condition a chat can fulfil.
 *
 * Every rule is decided from what the chat list already knows about a chat, so folders work
 * without asking TDLib for anything extra and stay correct while the list is still loading.
 */
enum class FolderRule {

    /** The chat has unread messages, mentions, or is marked as unread. */
    UNREAD,

    /** Notifications of the chat are on. */
    UNMUTED,

    /** Notifications of the chat are muted. */
    MUTED,

    /** The chat is pinned in the list. */
    PINNED,

    /** A one to one conversation. */
    PRIVATE,

    /** A basic group or a supergroup. */
    GROUPS,

    /** A broadcast channel. */
    CHANNELS,

    /** Saved Messages. */
    SAVED_MESSAGES
}

/** How the rules of a folder are combined. */
enum class FolderRuleMode {

    /** A chat is in the folder when it fulfils at least one rule. */
    ANY,

    /** A chat is in the folder when it fulfils every rule. */
    ALL
}

/**
 * A tab of the chat list.
 *
 * Folders live on this device only: they are a way to look at the chat list, not a Telegram
 * object, so they neither sync to other devices nor change anything on the server.
 */
data class ChatFolder(
    val id: Long,
    val name: String,
    val rules: Set<FolderRule> = emptySet(),
    val ruleMode: FolderRuleMode = FolderRuleMode.ANY,
    val includeChatIds: Set<Long> = emptySet(),
    val excludeChatIds: Set<Long> = emptySet()
) {

    /** True when the folder can hold anything at all. */
    val isUsable: Boolean get() = rules.isNotEmpty() || includeChatIds.isNotEmpty()

    /** A copy with the name trimmed and cut to the length a tab label can show. */
    fun withSanitizedName(raw: String): ChatFolder = copy(
        name = raw.trim().take(MAX_NAME_LENGTH).ifBlank { name }
    )

    companion object {

        /** Length a tab label can show without being cut off. */
        const val MAX_NAME_LENGTH = 24

        /** How many folders the tab strip can stay readable with. */
        const val MAX_FOLDERS = 12

        /** Chats a folder may name by hand; keeps the stored form small. */
        const val MAX_NAMED_CHATS = 200
    }
}

/** A folder together with what it holds right now, which is what the tab strip renders. */
data class ChatFolderUi(
    val id: Long,
    val name: String,
    val unreadCount: Int,
    val hasMentions: Boolean
)
