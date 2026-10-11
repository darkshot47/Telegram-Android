package com.telefarm.core.folders

import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which chats a folder holds. */
class FolderMatcherTest {

    @Test
    fun `a folder without a selected tab is the whole list`() {
        val chats = listOf(chat(1, ChatKind.PRIVATE), chat(2, ChatKind.CHANNEL))
        assertEquals(chats, FolderMatcher.select(null, chats))
    }

    @Test
    fun `any rule is enough in the default mode`() {
        val folder = folder(rules = setOf(FolderRule.CHANNELS, FolderRule.UNREAD))
        assertTrue(FolderMatcher.matches(folder, chat(1, ChatKind.CHANNEL)))
        assertTrue(FolderMatcher.matches(folder, chat(2, ChatKind.PRIVATE, unreadCount = 3)))
        assertFalse(FolderMatcher.matches(folder, chat(3, ChatKind.PRIVATE)))
    }

    @Test
    fun `all rules have to hold in the strict mode`() {
        val folder = folder(
            rules = setOf(FolderRule.GROUPS, FolderRule.UNMUTED),
            mode = FolderRuleMode.ALL
        )
        assertTrue(FolderMatcher.matches(folder, chat(1, ChatKind.SUPERGROUP)))
        assertFalse(FolderMatcher.matches(folder, chat(2, ChatKind.SUPERGROUP, isMuted = true)))
        assertFalse(FolderMatcher.matches(folder, chat(3, ChatKind.PRIVATE)))
    }

    @Test
    fun `an excluded chat stays out even when the rules want it`() {
        val folder = folder(
            rules = setOf(FolderRule.UNMUTED),
            exclude = setOf(7L)
        )
        assertTrue(FolderMatcher.matches(folder, chat(1, ChatKind.PRIVATE)))
        assertFalse(FolderMatcher.matches(folder, chat(7, ChatKind.PRIVATE)))
    }

    @Test
    fun `exclusion wins over a chat that was added by hand`() {
        val folder = folder(include = setOf(7L), exclude = setOf(7L))
        assertFalse(FolderMatcher.matches(folder, chat(7, ChatKind.PRIVATE)))
    }

    @Test
    fun `a chat that was added by hand is in the folder whatever the rules say`() {
        val folder = folder(rules = setOf(FolderRule.CHANNELS), include = setOf(9L))
        assertTrue(FolderMatcher.matches(folder, chat(9, ChatKind.PRIVATE)))
        assertFalse(FolderMatcher.matches(folder, chat(10, ChatKind.PRIVATE)))
    }

    @Test
    fun `a folder without rules and without named chats is empty`() {
        val folder = folder()
        assertFalse(folder.isUsable)
        assertEquals(emptyList<ChatUi>(), FolderMatcher.select(folder, listOf(chat(1, ChatKind.PRIVATE))))
    }

    @Test
    fun `every rule reads the property it names`() {
        assertTrue(FolderMatcher.matchesRule(FolderRule.UNREAD, chat(1, ChatKind.PRIVATE, unreadCount = 2)))
        assertFalse(FolderMatcher.matchesRule(FolderRule.UNREAD, chat(1, ChatKind.PRIVATE)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.MUTED, chat(1, ChatKind.PRIVATE, isMuted = true)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.UNMUTED, chat(1, ChatKind.PRIVATE)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.PINNED, chat(1, ChatKind.PRIVATE, isPinned = true)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.PRIVATE, chat(1, ChatKind.PRIVATE)))
        assertFalse(FolderMatcher.matchesRule(FolderRule.PRIVATE, chat(1, ChatKind.SECRET)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.GROUPS, chat(1, ChatKind.BASIC_GROUP)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.GROUPS, chat(1, ChatKind.SUPERGROUP)))
        assertFalse(FolderMatcher.matchesRule(FolderRule.GROUPS, chat(1, ChatKind.CHANNEL)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.CHANNELS, chat(1, ChatKind.CHANNEL)))
        assertTrue(FolderMatcher.matchesRule(FolderRule.SAVED_MESSAGES, chat(1, ChatKind.SAVED_MESSAGES)))
    }

    @Test
    fun `the badge counts the unread messages of the folder only`() {
        val folder = folder(rules = setOf(FolderRule.CHANNELS))
        val chats = listOf(
            chat(1, ChatKind.CHANNEL, unreadCount = 4),
            chat(2, ChatKind.CHANNEL, unreadCount = 1, mentions = 1),
            chat(3, ChatKind.PRIVATE, unreadCount = 50)
        )
        val summary = FolderMatcher.summarize(folder, chats)
        assertEquals(5, summary.unreadCount)
        assertTrue(summary.hasMentions)
        assertEquals(folder.name, summary.name)
    }

    @Test
    fun `a folder that holds nothing has no badge and no mentions`() {
        val summary = FolderMatcher.summarize(folder(rules = setOf(FolderRule.MUTED)), listOf(chat(1, ChatKind.PRIVATE)))
        assertEquals(0, summary.unreadCount)
        assertFalse(summary.hasMentions)
    }

    @Test
    fun `every folder is summarized in tab order`() {
        // "Unread and not muted" against "muted": the first chat is unread and unmuted, the
        // second is unread but muted, so each folder holds exactly one of them.
        val folders = listOf(
            folder(id = 2, rules = setOf(FolderRule.UNREAD, FolderRule.UNMUTED), mode = FolderRuleMode.ALL),
            folder(id = 3, rules = setOf(FolderRule.MUTED))
        )
        val chats = listOf(
            chat(1, ChatKind.PRIVATE, unreadCount = 2),
            chat(2, ChatKind.PRIVATE, isMuted = true, unreadCount = 5)
        )
        val summaries = FolderMatcher.summarizeAll(folders, chats)
        assertEquals(listOf(2L, 3L), summaries.map { it.id })
        assertEquals(listOf(2, 5), summaries.map { it.unreadCount })
    }

    @Test
    fun `an unread muted chat counts in both the unread and the muted folder`() {
        val unread = FolderMatcher.summarize(folder(rules = setOf(FolderRule.UNREAD)), listOf(chat(1, ChatKind.PRIVATE, unreadCount = 3, isMuted = true)))
        val muted = FolderMatcher.summarize(folder(rules = setOf(FolderRule.MUTED)), listOf(chat(1, ChatKind.PRIVATE, unreadCount = 3, isMuted = true)))
        assertEquals(3, unread.unreadCount)
        assertEquals(3, muted.unreadCount)
    }

    @Test
    fun `selecting keeps the order of the list`() {
        val folder = folder(rules = setOf(FolderRule.UNMUTED))
        val chats = listOf(chat(1, ChatKind.PRIVATE, isMuted = true), chat(2, ChatKind.PRIVATE), chat(3, ChatKind.PRIVATE))
        assertEquals(listOf(2L, 3L), FolderMatcher.select(folder, chats).map { it.id })
    }

    @Test
    fun `a name is trimmed and cut to what a tab can show`() {
        val long = "a".repeat(ChatFolder.MAX_NAME_LENGTH + 30)
        val folder = folder(name = "ignored").withSanitizedName("  Work  ")
        assertEquals("Work", folder.name)
        assertEquals(ChatFolder.MAX_NAME_LENGTH, folder.withSanitizedName(long).name.length)
        // An empty name keeps the one that is there instead of producing a tab without a label.
        assertEquals("Work", folder.withSanitizedName("   ").name)
    }

    private fun folder(
        id: Long = 1,
        name: String = "Folder",
        rules: Set<FolderRule> = emptySet(),
        mode: FolderRuleMode = FolderRuleMode.ANY,
        include: Set<Long> = emptySet(),
        exclude: Set<Long> = emptySet()
    ) = ChatFolder(id = id, name = name, rules = rules, ruleMode = mode, includeChatIds = include, excludeChatIds = exclude)

    private fun chat(
        id: Long,
        kind: ChatKind,
        unreadCount: Int = 0,
        mentions: Int = 0,
        isMuted: Boolean = false,
        isPinned: Boolean = false,
        markedAsUnread: Boolean = false
    ) = ChatUi(
        id = id,
        title = "chat $id",
        kind = kind,
        photo = null,
        lastMessagePreview = null,
        lastMessageDate = 0,
        lastMessageIsOutgoing = false,
        lastMessageIsEdited = false,
        lastMessageSenderName = null,
        unreadCount = unreadCount,
        unreadMentionCount = mentions,
        isMarkedAsUnread = markedAsUnread,
        isPinned = isPinned,
        isMuted = isMuted,
        canSendMessages = true,
        draftText = null,
        order = 0L
    )
}
