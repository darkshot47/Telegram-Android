package com.telefarm.core.reactions

/** One reaction on a message: what it is, how many, and whether this account made one of them. */
data class ReactionUi(
    val emoji: String,
    val count: Int,
    val isChosen: Boolean
)

/**
 * Reacting to a message.
 *
 * A reaction is the shortest possible answer - it says "seen" without asking anybody to read
 * anything. That only works if putting one on a message takes one tap, so the ones worth having
are on offer without a search, and choosing one is a toggle rather than a form.
 *
 * Nothing here knows about Telegram. What a reaction does to a message is the repository's
 * business; what the strip under the bubble says, and which emoji replaces which, is arithmetic
 * that has to be the same every time it is drawn.
 */
object Reactions {

    /**
     * The reactions offered straight from the message.
     *
     * Eight, because a list you have to scroll to find the obvious one in is not quicker than
     * typing the reply.
     */
    val QUICK = listOf("👍", "👎", "❤️", "🔥", "🎉", "😂", "😮", "😢")

    /**
     * The emoji to send for a reaction, given what this account has already sent.
     *
     * Choosing a reaction already chosen takes it away, which is how a reaction is undone; the
     * answer is then an empty list. Telegram replaces the whole set rather than adding to it, so
     * a reaction cannot be doubled and a mistake cannot pile up.
     *
     * @return the reactions to send.
     */
    fun toggle(chosen: List<String>, emoji: String): List<String> =
        if (emoji in chosen) emptyList() else listOf(emoji)

    /**
     * What the strip under the bubble says: `👍 2 ❤️ 1`.
     *
     * An empty strip means no reactions, and is drawn as nothing rather than as a blank line.
     */
    fun label(reactions: List<ReactionUi>): String =
        sort(reactions).joinToString(" ") { reaction ->
            if (reaction.count > 1) "${reaction.emoji} ${reaction.count}" else reaction.emoji
        }

    /**
     * Reactions in the order they are read.
     *
     * The one this account chose comes first, so it can be seen without reading the strip; then
     * the most used; then by the emoji itself. Emoji sort by their code units, which is not the
     * order they read in, but it is the same order on every draw - and a strip that rearranges
     * itself between two drawings of one message is worse than one that is not sorted at all.
     */
    fun sort(reactions: List<ReactionUi>): List<ReactionUi> =
        reactions.sortedWith(compareByDescending<ReactionUi> { it.isChosen }
            .thenByDescending { it.count }
            .thenBy { it.emoji })

    /** The reactions this account has already put on a message. */
    fun chosen(reactions: List<ReactionUi>): List<String> =
        reactions.filter { it.isChosen }.map { it.emoji }

    /** True when this account has reacted to the message. */
    fun hasChosen(reactions: List<ReactionUi>): Boolean = reactions.any { it.isChosen }
}
