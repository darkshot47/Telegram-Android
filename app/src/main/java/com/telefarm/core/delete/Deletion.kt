package com.telefarm.core.delete

/** How far a deletion reaches. */
enum class DeletionScope {

    /** The message goes for everybody who could see it. */
    EVERYONE,

    /** The message goes from this account's copy and nobody else's. */
    ONLY_ME
}

/**
 * Deleting a message, and how far the deletion reaches.
 *
 * There are two different acts behind one word. Deleting your own message for everybody takes back
 * something you said. Deleting only for yourself tidies your own list and leaves everybody else's
> copy exactly as it was - and deleting somebody else's message for everybody takes it away from
 * them too, which is not what "delete" usually means to the person tapping it.
 *
 * Telegram says which of the two a message allows, so the choice is read rather than assumed. A
 * client that always deletes for everybody will silently take words out of another person's
 * conversation; one that always deletes for yourself will leave up something the writer asked to
 * have taken down.
 *
 * Nothing here knows about Telegram. Which flags a message carries is the repository's business;
 * what they mean is arithmetic that has to come out the same every time.
 */
object Deletion {

    /**
     * What can be done with a message, or null when it cannot be deleted at all.
     *
     * Deleting for everybody wins when both are offered, because that is the one that cannot be
     * undone by asking again: a message deleted only for yourself is still there for everybody
     * else, so nothing has been lost by choosing it first.
     *
     * @param forAllUsers Telegram says it can be deleted for everybody.
     * @param forSelfOnly Telegram says it can be deleted for this account alone.
     */
    fun scope(forAllUsers: Boolean, forSelfOnly: Boolean): DeletionScope? = when {
        forAllUsers -> DeletionScope.EVERYONE
        forSelfOnly -> DeletionScope.ONLY_ME
        else -> null
    }

    /**
     * The choices to offer, widest first.
     *
     * One choice when only one is possible, so a person is not asked a question with one answer.
     * None when the message cannot be deleted, which is better than an action that fails.
     */
    fun choices(forAllUsers: Boolean, forSelfOnly: Boolean): List<DeletionScope> =
        listOfNotNull(
            DeletionScope.EVERYONE.takeIf { forAllUsers },
            DeletionScope.ONLY_ME.takeIf { forSelfOnly || forAllUsers }
        )

    /** True when the deletion will change what somebody else sees. */
    fun reachesOthers(scope: DeletionScope): Boolean = scope == DeletionScope.EVERYONE
}
