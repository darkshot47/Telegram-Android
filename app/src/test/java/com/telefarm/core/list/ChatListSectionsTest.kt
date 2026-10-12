package com.telefarm.core.list

import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section labels of the chat list. */
class ChatListSectionsTest {

    private val today = 20_000L

    @Test
    fun `an empty list has no sections`() {
        assertEquals(emptyList<ChatListEntry>(), ChatListSections.section(emptyList(), today))
    }

    @Test
    fun `one chat gets one header`() {
        val entries = ChatListSections.section(listOf(chat(1, secondsOnDay(today))), today)
        assertEquals(listOf<ChatListEntry>(ChatListEntry.Header(DateBucket.TODAY), ChatListEntry.Item(chat(1, secondsOnDay(today)))), entries)
    }

    @Test
    fun `chats of the same age share one header`() {
        val chats = listOf(chat(1, secondsOnDay(today)), chat(2, secondsOnDay(today)), chat(3, secondsOnDay(today)))
        assertEquals(1, ChatListSections.headerCount(ChatListSections.section(chats, today)))
        assertEquals(4, ChatListSections.section(chats, today).size)
    }

    @Test
    fun `a new age starts a new section`() {
        val chats = listOf(
            chat(1, secondsOnDay(today)),
            chat(2, secondsOnDay(today - 1)),
            chat(3, secondsOnDay(today - 3)),
            chat(4, secondsOnDay(today - 20)),
            chat(5, secondsOnDay(today - 400))
        )
        val entries = ChatListSections.section(chats, today)
        assertEquals(
            listOf(
                DateBucket.TODAY, DateBucket.YESTERDAY, DateBucket.THIS_WEEK,
                DateBucket.THIS_MONTH, DateBucket.EARLIER
            ),
            entries.filterIsInstance<ChatListEntry.Header>().map { it.bucket }
        )
        assertEquals(5, entries.filterIsInstance<ChatListEntry.Item>().size)
    }

    @Test
    fun `a chat out of age order joins the section that is already there`() {
        // A pinned chat of last month between two of today's chats must not open a second month
        // section, and must not repeat the label of today either.
        val chats = listOf(
            chat(1, secondsOnDay(today)),
            chat(9, secondsOnDay(today - 20)),
            chat(2, secondsOnDay(today))
        )
        val headers = ChatListSections.section(chats, today).filterIsInstance<ChatListEntry.Header>()
        assertEquals(listOf(DateBucket.TODAY, DateBucket.THIS_MONTH), headers.map { it.bucket })
        assertEquals(2, ChatListSections.headerCount(ChatListSections.section(chats, today)))
    }

    @Test
    fun `a pinned old chat on top opens its own section first`() {
        val chats = listOf(chat(9, secondsOnDay(today - 20)), chat(1, secondsOnDay(today)))
        val headers = ChatListSections.section(chats, today).filterIsInstance<ChatListEntry.Header>()
        assertEquals(listOf(DateBucket.THIS_MONTH, DateBucket.TODAY), headers.map { it.bucket })
    }

    @Test
    fun `the day boundaries are where they read`() {
        assertEquals(DateBucket.TODAY, ChatListSections.bucketOf(secondsOnDay(today), today))
        assertEquals(DateBucket.YESTERDAY, ChatListSections.bucketOf(secondsOnDay(today - 1), today))
        assertEquals(DateBucket.THIS_WEEK, ChatListSections.bucketOf(secondsOnDay(today - 2), today))
        assertEquals(DateBucket.THIS_WEEK, ChatListSections.bucketOf(secondsOnDay(today - 6), today))
        assertEquals(DateBucket.THIS_MONTH, ChatListSections.bucketOf(secondsOnDay(today - 7), today))
        assertEquals(DateBucket.THIS_MONTH, ChatListSections.bucketOf(secondsOnDay(today - 29), today))
        assertEquals(DateBucket.EARLIER, ChatListSections.bucketOf(secondsOnDay(today - 30), today))
    }

    @Test
    fun `a chat without a date is the oldest section`() {
        assertEquals(DateBucket.EARLIER, ChatListSections.bucketOf(0, today))
        assertEquals(DateBucket.EARLIER, ChatListSections.bucketOf(-5, today))
    }

    @Test
    fun `a chat from the future is today`() {
        assertEquals(DateBucket.TODAY, ChatListSections.bucketOf(secondsOnDay(today + 2), today))
    }

    @Test
    fun `every row has a key that identifies it`() {
        val chats = listOf(chat(1, secondsOnDay(today)), chat(2, secondsOnDay(today - 400)))
        val keys = ChatListSections.section(chats, today).map { it.key }
        assertEquals(keys.distinct(), keys)
        assertTrue(keys.contains("chat:1"))
        assertTrue(keys.contains("header:TODAY"))
    }

    /** Noon of the day [epochDay] days after the epoch, in seconds. */
    private fun secondsOnDay(epochDay: Long): Int = (epochDay * 86_400L + 43_200L).toInt()

    private fun chat(id: Long, timestampSeconds: Int) = ChatUi(
        id = id,
        title = "chat $id",
        kind = ChatKind.PRIVATE,
        photo = null,
        lastMessagePreview = null,
        lastMessageDate = timestampSeconds,
        lastMessageIsOutgoing = false,
        lastMessageIsEdited = false,
        lastMessageSenderName = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        isMarkedAsUnread = false,
        isPinned = false,
        isArchived = false,
        isMuted = false,
        muteForSeconds = 0,
        canSendMessages = true,
        draftText = null,
        order = 0L
    )
}
