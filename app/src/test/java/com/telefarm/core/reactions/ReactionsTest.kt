package com.telefarm.core.reactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reacting to a message. */
class ReactionsTest {

    @Test
    fun `choosing a reaction sends it alone`() {
        assertEquals(listOf("👍"), Reactions.toggle(emptyList(), "👍"))
    }

    @Test
    fun `choosing the reaction already chosen takes it away`() {
        assertTrue(Reactions.toggle(listOf("👍"), "👍").isEmpty())
    }

    @Test
    fun `a reaction replaces another rather than adding to it`() {
        assertEquals(listOf("🔥"), Reactions.toggle(listOf("👍"), "🔥"))
    }

    @Test
    fun `undoing one of several still sends nothing, because only one can be chosen`() {
        assertTrue(Reactions.toggle(listOf("👍", "🔥"), "👍").isEmpty())
    }

    @Test
    fun `a strip with nothing on it reads as nothing`() {
        assertEquals("", Reactions.label(emptyList()))
    }

    @Test
    fun `a single reaction is drawn without a number`() {
        assertEquals("👍", Reactions.label(listOf(ReactionUi("👍", 1, false))))
    }

    @Test
    fun `a reaction more than one person made carries its number`() {
        assertEquals("👍 3", Reactions.label(listOf(ReactionUi("👍", 3, false))))
    }

    @Test
    fun `reactions are drawn most used first`() {
        val label = Reactions.label(
            listOf(ReactionUi("❤️", 1, false), ReactionUi("👍", 5, false), ReactionUi("🔥", 2, false))
        )
        assertEquals("👍 5 🔥 2 ❤️", label)
    }

    @Test
    fun `the reaction this account made comes first whatever its number`() {
        val sorted = Reactions.sort(
            listOf(ReactionUi("👍", 9, false), ReactionUi("🔥", 1, true))
        )
        assertEquals("🔥", sorted.first().emoji)
        assertTrue(sorted.first().isChosen)
    }

    @Test
    fun `a tie is settled the same way every time so a strip does not rearrange itself`() {
        val shuffled = listOf(
            ReactionUi("🎉", 2, false),
            ReactionUi("😂", 2, false),
            ReactionUi("❤️", 2, false)
        )
        val sorted = Reactions.sort(shuffled).map { it.emoji }
        // Emoji sort by their code units, which is not the order they read in but is the same
        // order on every draw - and a strip that rearranges itself is worse than one that does
        // not sort at all.
        assertEquals(sorted, Reactions.sort(shuffled.reversed()).map { it.emoji })
        assertEquals(listOf("❤️", "🎉", "😂"), sorted)
    }

    @Test
    fun `sorting does not lose or invent reactions`() {
        val reactions = listOf(
            ReactionUi("👍", 5, false),
            ReactionUi("🔥", 1, true),
            ReactionUi("😂", 3, false)
        )
        assertEquals(3, Reactions.sort(reactions).size)
        assertEquals(9, Reactions.sort(reactions).sumOf { it.count })
    }

    @Test
    fun `the chosen reactions are the ones this account made`() {
        val reactions = listOf(
            ReactionUi("👍", 5, false),
            ReactionUi("🔥", 1, true),
            ReactionUi("😂", 3, false)
        )
        assertEquals(listOf("🔥"), Reactions.chosen(reactions))
        assertTrue(Reactions.hasChosen(reactions))
    }

    @Test
    fun `a message with no reaction of its own says so`() {
        assertFalse(Reactions.hasChosen(listOf(ReactionUi("👍", 5, false))))
        assertTrue(Reactions.chosen(emptyList()).isEmpty())
    }

    @Test
    fun `the quick list holds eight and no duplicates`() {
        assertEquals(8, Reactions.QUICK.size)
        assertEquals(Reactions.QUICK.size, Reactions.QUICK.distinct().size)
    }

    @Test
    fun `every quick reaction can be toggled on and off`() {
        for (emoji in Reactions.QUICK) {
            assertEquals(listOf(emoji), Reactions.toggle(emptyList(), emoji))
            assertTrue(Reactions.toggle(listOf(emoji), emoji).isEmpty())
        }
    }
}
