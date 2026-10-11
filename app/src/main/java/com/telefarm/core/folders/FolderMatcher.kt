package com.telefarm.core.folders

import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.ChatFolderUi
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode

/**
 * Decides which chats belong to a folder.
 *
 * The order is fixed and simple to reason about:
 *
 * 1. a chat the folder names explicitly is in it,
 * 2. a chat the folder excludes is not in it, even when it was named,
 * 3. otherwise the rules decide, combined as [FolderRuleMode] asks.
 *
 * Exclusion wins over inclusion, because removing one loud chat from an "everything unmuted"
 * folder is the thing people actually want to do.
 */
object FolderMatcher {

    /** True when [chat] belongs to [folder]. */
    fun matches(folder: ChatFolder, chat: ChatUi): Boolean {
        if (chat.id in folder.excludeChatIds) return false
        if (chat.id in folder.includeChatIds) return true
        if (folder.rules.isEmpty()) return false
        return when (folder.ruleMode) {
            FolderRuleMode.ANY -> folder.rules.any { matchesRule(it, chat) }
            FolderRuleMode.ALL -> folder.rules.all { matchesRule(it, chat) }
        }
    }

    /** True when [chat] fulfils [rule]. */
    fun matchesRule(rule: FolderRule, chat: ChatUi): Boolean = when (rule) {
        FolderRule.UNREAD -> chat.hasUnread
        FolderRule.UNMUTED -> !chat.isMuted
        FolderRule.MUTED -> chat.isMuted
        FolderRule.PINNED -> chat.isPinned
        FolderRule.PRIVATE -> chat.kind == ChatKind.PRIVATE
        FolderRule.GROUPS -> chat.kind.isGroup
        FolderRule.CHANNELS -> chat.kind.isChannel
        FolderRule.SAVED_MESSAGES -> chat.kind == ChatKind.SAVED_MESSAGES
    }

    /**
     * The chats of [folder], in the order they already have.
     *
     * A null folder means "no folder is selected", which is the whole list - the first tab.
     */
    fun select(folder: ChatFolder?, chats: List<ChatUi>): List<ChatUi> {
        if (folder == null) return chats
        return chats.filter { matches(folder, it) }
    }

    /** Unread badge of [folder], counted over [chats]. */
    fun summarize(folder: ChatFolder, chats: List<ChatUi>): ChatFolderUi {
        var unread = 0
        var mentions = false
        for (chat in chats) {
            if (!matches(folder, chat)) continue
            unread += chat.unreadCount
            if (chat.unreadMentionCount > 0) mentions = true
        }
        return ChatFolderUi(
            id = folder.id,
            name = folder.name,
            unreadCount = unread.coerceAtLeast(0),
            hasMentions = mentions
        )
    }

    /** Badge of every folder at once, in the order the tabs are shown in. */
    fun summarizeAll(folders: List<ChatFolder>, chats: List<ChatUi>): List<ChatFolderUi> =
        folders.map { summarize(it, chats) }
}
