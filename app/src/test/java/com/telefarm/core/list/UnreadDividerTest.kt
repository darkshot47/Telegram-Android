package com.telefarm.core.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the line between read and unread goes.
 *
 * Messages here are pairs of an identifier and whether they came from somebody else, which is all
 * the rule looks at.
 */
class UnreadDividerTest {

    /** An incoming message. */
    private fun incoming(id: Long) = Message(id, incoming = true)

    /** A message this account sent. */
    private fun outgoing(id: Long) = Message(id, incoming = false)

    private data class Message(val id: Long, val incoming: Boolean)

    private fun position(messages: List<Message>, read: Long) = UnreadDivider.position(
        messages = messages,
        lastReadInboxMessageId = read,
        isIncoming = { it.incoming },
        idOf = { it.id }
    )

    @Test
    fun `the line goes before the first unread incoming message`() {
        val messages = listOf(incoming(1), incoming(2), incoming(3))
        assertEquals(1, position(messages, 1))
    }

    @Test
    fun `nothing unread means no line`() {
        val messages = listOf(incoming(1), incoming(2), incoming(3))
        assertNull(position(messages, 3))
    }

    @Test
    fun `a conversation opened on somebody else's words gets the line at the top`() {
        assertEquals(0, position(listOf(incoming(5), incoming(6)), 0))
    }

    @Test
    fun `your own unread messages are not something to mark`() {
        val messages = listOf(outgoing(1), outgoing(2), outgoing(3))
        assertNull(position(messages, 0))
    }

    @Test
    fun `the line skips your own messages to reach the first unread one`() {
        val messages = listOf(outgoing(1), outgoing(2), incoming(3))
        assertEquals(2, position(messages, 0))
    }

    @Test
    fun `an unread message after a run of read ones is marked where it starts`() {
        val messages = listOf(incoming(1), incoming(2), outgoing(3), incoming(4), incoming(5))
        assertEquals(3, position(messages, 2))
    }

    @Test
    fun `read messages after the line do not move it`() {
        val messages = listOf(incoming(1), outgoing(2), incoming(3), incoming(4))
        assertEquals(2, position(messages, 1))
    }

    @Test
    fun `an empty conversation has no line`() {
        assertNull(position(emptyList(), 0))
    }

    @Test
    fun `a conversation that has never been read marks its first incoming message`() {
        val messages = listOf(outgoing(1), incoming(2), incoming(3))
        assertEquals(1, position(messages, 0))
    }

    @Test
    fun `the newest message being read leaves nothing to mark`() {
        val messages = listOf(incoming(7), incoming(8), incoming(9))
        assertNull(position(messages, 9))
    }

    @Test
    fun `a read mark newer than anything on screen marks nothing`() {
        val messages = listOf(incoming(1), incoming(2))
        assertNull(position(messages, 99))
    }

    @Test
    fun `is needed agrees with where the line goes`() {
        val messages = listOf(incoming(1), incoming(2))
        assertTrue(UnreadDivider.isNeeded(messages, 1, { it.incoming }, { it.id }))
        assertFalse(UnreadDivider.isNeeded(messages, 2, { it.incoming }, { it.id }))
    }

    @Test
    fun `a single unread message is marked`() {
        assertEquals(0, position(listOf(incoming(4)), 0))
    }

    @Test
    fun `identifiers out of order still find the first unread one in the order given`() {
        // The conversation is handed over oldest first; a message with a smaller identifier later
        // in the list is not reordered, because the line follows what is drawn.
        val messages = listOf(incoming(9), incoming(4))
        assertEquals(0, position(messages, 0))
    }
}
