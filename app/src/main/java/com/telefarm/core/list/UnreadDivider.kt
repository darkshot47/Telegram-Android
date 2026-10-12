package com.telefarm.core.list

/**
 * Where the line between what has been read and what has not goes.
 *
 * A conversation that opens at the newest message and gives no mark of where it was left is one
 * that has to be scrolled up to be sure of. The mark is a line, and putting it in the wrong place
 * is worse than not having it: too high and everything looks new, too low and messages are missed.
 *
 * Two rules decide it, and both follow from what the line is for:
 *
 * - only **incoming** messages can be unread. Your own messages were read by you as you wrote
 *   them, so a conversation you have been talking in alone has nothing to mark.
 * - the line goes before the **first** such message, and not before the first message of the
 *   conversation. Those are the same thing only when the conversation opens with somebody else's
 *   words.
 */
object UnreadDivider {

    /**
     * The index to put the line at, or null when there is nothing unread.
     *
     * @param messages the conversation in ascending order, the oldest first.
     * @param lastReadInboxMessageId the newest incoming message already read, 0 when none has been.
     * @param isIncoming whether a message came from somebody else.
     * @param idOf the identifier of a message, which is what "already read" is measured by.
     */
    fun <T> position(
        messages: List<T>,
        lastReadInboxMessageId: Long,
        isIncoming: (T) -> Boolean,
        idOf: (T) -> Long
    ): Int? {
        for (index in messages.indices) {
            val message = messages[index]
            if (isIncoming(message) && idOf(message) > lastReadInboxMessageId) return index
        }
        return null
    }

    /**
     * True when the line belongs in the conversation at all.
     *
     * A conversation with unread messages that are not on screen - older history that has not been
     * loaded - gets no line, because a line pointing at a message nobody can see points at
     * nothing.
     */
    fun <T> isNeeded(
        messages: List<T>,
        lastReadInboxMessageId: Long,
        isIncoming: (T) -> Boolean,
        idOf: (T) -> Long
    ): Boolean = position(messages, lastReadInboxMessageId, isIncoming, idOf) != null
}
