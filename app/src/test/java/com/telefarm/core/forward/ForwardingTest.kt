package com.telefarm.core.forward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forwarding a selection of messages. */
class ForwardingTest {

    @Test
    fun `a selection tapped in any order goes in increasing order`() {
        assertEquals(listOf(1L, 5L, 9L), Forwarding.ordered(listOf(9L, 1L, 5L)))
    }

    @Test
    fun `a message selected twice is forwarded once`() {
        assertEquals(listOf(3L, 7L), Forwarding.ordered(listOf(7L, 3L, 7L, 3L)))
    }

    @Test
    fun `an empty selection is no forward at all`() {
        assertTrue(Forwarding.batches(emptyList()).isEmpty())
        assertTrue(Forwarding.ordered(emptyList()).isEmpty())
    }

    @Test
    fun `a selection within the limit is one forward`() {
        val ids = (1L..50L).toList()
        val batches = Forwarding.batches(ids)
        assertEquals(1, batches.size)
        assertEquals(50, batches.first().size)
    }

    @Test
    fun `exactly the limit is one forward`() {
        val ids = (1L..Forwarding.MAX_MESSAGES.toLong()).toList()
        assertEquals(1, Forwarding.batches(ids).size)
    }

    @Test
    fun `one message over the limit becomes two forwards`() {
        val ids = (1L..(Forwarding.MAX_MESSAGES + 1).toLong()).toList()
        val batches = Forwarding.batches(ids)
        assertEquals(2, batches.size)
        assertEquals(Forwarding.MAX_MESSAGES, batches[0].size)
        assertEquals(1, batches[1].size)
    }

    @Test
    fun `two hundred messages become two forwards of a hundred`() {
        val ids = (1L..200L).toList()
        val batches = Forwarding.batches(ids)
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.size == Forwarding.MAX_MESSAGES })
    }

    @Test
    fun `every batch is sendable`() {
        val ids = (1L..250L).shuffled().toList()
        assertTrue(Forwarding.batches(ids).all { Forwarding.isSendable(it) })
    }

    @Test
    fun `a batch out of order is not sendable`() {
        assertFalse(Forwarding.isSendable(listOf(5L, 1L)))
    }

    @Test
    fun `a batch holding one identifier twice is not sendable`() {
        assertFalse(Forwarding.isSendable(listOf(5L, 5L)))
    }

    @Test
    fun `an empty batch is not sendable`() {
        assertFalse(Forwarding.isSendable(emptyList()))
    }

    @Test
    fun `a batch over the limit is not sendable`() {
        val batch = (1L..(Forwarding.MAX_MESSAGES + 1).toLong()).toList()
        assertFalse(Forwarding.isSendable(batch))
    }

    @Test
    fun `a message that cannot leave the chat is left out`() {
        val sendable = Forwarding.sendable(listOf(1L, 2L, 3L)) { it != 2L }
        assertEquals(listOf(1L, 3L), sendable)
    }

    @Test
    fun `the messages left out are counted so the person can be told`() {
        assertEquals(2, Forwarding.dropped(listOf(1L, 2L, 3L, 4L)) { it > 2L })
    }

    @Test
    fun `a selection where nothing can be forwarded sends nothing`() {
        assertTrue(Forwarding.sendable(listOf(1L, 2L)) { false }.isEmpty())
        assertEquals(2, Forwarding.dropped(listOf(1L, 2L)) { false })
    }

    @Test
    fun `a selection where everything can be forwarded drops nothing`() {
        assertEquals(0, Forwarding.dropped(listOf(1L, 2L)) { true })
    }

    @Test
    fun `ordering does not lose or invent messages`() {
        val ids = listOf(40L, 12L, 40L, 7L, 99L, 12L)
        assertEquals(listOf(7L, 12L, 40L, 99L), Forwarding.ordered(ids))
        assertEquals(4, Forwarding.ordered(ids).size)
    }
}
