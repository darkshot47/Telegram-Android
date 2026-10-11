package com.telefarm.core.list

import com.telefarm.data.model.ChatUi
import java.util.EnumSet

/** How old the newest message of a chat is, which is what the section of the list says. */
enum class DateBucket {

    /** Today. */
    TODAY,

    /** Yesterday. */
    YESTERDAY,

    /** The last seven days, today and yesterday apart. */
    THIS_WEEK,

    /** Older than a week, but inside the last thirty days. */
    THIS_MONTH,

    /** Everything older. */
    EARLIER
}

/** One row of the chat list: either the label of a section or a chat. */
sealed interface ChatListEntry {

    /** Stable identity of the row, for the diff of the list. */
    val key: String

    /** Section label; one per run of chats of the same age. */
    data class Header(val bucket: DateBucket) : ChatListEntry {
        override val key: String get() = "header:${bucket.name}"
    }

    /** A chat. */
    data class Item(val chat: ChatUi) : ChatListEntry {
        override val key: String get() = "chat:${chat.id}"
    }
}

/**
 * Splits the chat list into sections by the age of the newest message.
 *
 * A label is written the first time its age appears and never again. Pinned chats are not in
 * age order, and a section that appears, disappears and appears again would read as a bug, so a
 * pinned chat of last month sitting between two of today's chats joins the month section that is
 * already on screen instead of opening a second one.
 */
object ChatListSections {

    /** Chats a day is long, in days. */
    private const val DAYS_PER_WEEK = 7L
    private const val DAYS_PER_MONTH = 30L

    /** [chats] with a section header in front of every run of a new age. */
    fun section(chats: List<ChatUi>, todayEpochDay: Long): List<ChatListEntry> {
        if (chats.isEmpty()) return emptyList()
        val entries = ArrayList<ChatListEntry>(chats.size + DateBucket.entries.size)
        val written = EnumSet.noneOf(DateBucket::class.java)
        for (chat in chats) {
            val bucket = bucketOf(chat.lastMessageDate, todayEpochDay)
            if (written.add(bucket)) {
                entries += ChatListEntry.Header(bucket)
            }
            entries += ChatListEntry.Item(chat)
        }
        return entries
    }

    /** Section a chat of [timestampSeconds] belongs to on the day [todayEpochDay]. */
    fun bucketOf(timestampSeconds: Int, todayEpochDay: Long): DateBucket {
        if (timestampSeconds <= 0) return DateBucket.EARLIER
        val daysAgo = todayEpochDay - timestampSeconds.toLong() / SECONDS_PER_DAY
        return when {
            daysAgo <= 0 -> DateBucket.TODAY
            daysAgo == 1L -> DateBucket.YESTERDAY
            daysAgo < DAYS_PER_WEEK -> DateBucket.THIS_WEEK
            daysAgo < DAYS_PER_MONTH -> DateBucket.THIS_MONTH
            else -> DateBucket.EARLIER
        }
    }

    /** Number of sections [entries] shows. */
    fun headerCount(entries: List<ChatListEntry>): Int = entries.count { it is ChatListEntry.Header }

    private const val SECONDS_PER_DAY = 86_400L
}
