package com.telefarm.core.forward

/**
 * Forwarding messages to another chat.
 *
 * Telegram imposes three rules on a forward, and breaking any of them loses the whole request
 * rather than part of it - so they are applied here, before anything is sent:
 *
 * - the message identifiers have to be in a **strictly increasing** order, which a selection made
 *   by tapping down a conversation is not
 * - **at most 100** messages can be forwarded at once
 * - a message that cannot be forwarded - one from a channel that forbids it - has to be left out
 *
 * A selection that breaks one of these is fixed rather than refused, because the person chose
 * messages and not identifiers, and the difference between the two is not theirs to manage.
 */
object Forwarding {

    /** How many messages one forward can carry. */
    const val MAX_MESSAGES = 100

    /**
     * The identifiers of a selection, in the order a forward needs them.
     *
     * Sorted ascending, with anything selected twice counted once: a sequence that is strictly
     * increasing cannot hold the same identifier twice, and a selection can.
     */
    fun ordered(ids: Collection<Long>): List<Long> = ids.toSortedSet().toList()

    /**
     * The selection split into forwards, each within the limit.
     *
     * Two hundred messages become two forwards of a hundred rather than one request that is
     * refused. An empty selection gives no forwards at all, which is what stops a tap on
     * "forward" with nothing chosen from sending anything.
     */
    fun batches(ids: Collection<Long>): List<List<Long>> = ordered(ids).chunked(MAX_MESSAGES)

    /** True when a batch is in the order Telegram wants and within its size. */
    fun isSendable(batch: List<Long>): Boolean =
        batch.isNotEmpty() &&
            batch.size <= MAX_MESSAGES &&
            batch.indices.drop(1).all { batch[it] > batch[it - 1] }

    /**
     * The messages that will actually go, once the ones that cannot be forwarded are dropped.
     *
     * @param ids the selection.
     * @param canBeForwarded whether a given message may leave the chat it is in.
     */
    fun sendable(ids: Collection<Long>, canBeForwarded: (Long) -> Boolean): List<Long> =
        ordered(ids).filter(canBeForwarded)

    /** How many messages of a selection will not go, so the person can be told. */
    fun dropped(ids: Collection<Long>, canBeForwarded: (Long) -> Boolean): Int =
        ordered(ids).count { !canBeForwarded(it) }
}
