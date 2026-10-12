package com.telefarm.core.notifications

/** How much a notification says about the message behind it. */
enum class NotificationPreview {

    /** The message itself, in the notification. */
    FULL,

    /** Who wrote it, and not what they wrote. */
    SENDER,

    /** That a message arrived, and nothing more. */
    NONE
}

/**
 * What a notification says.
 *
 * A notification is read on a lock screen, which means it is read by whoever picks the phone up.
 * That is not a reason to hide messages, but it is a reason for the choice to belong to the person
 * whose messages they are - and for the three answers to mean what they say.
 *
 * The rule that matters is that a notification is never empty. A setting of "who wrote it" on a
 * message with no sender name has to fall back rather than show nothing, because a blank
 * notification says less than "new message" does.
 */
object NotificationPreviewText {

    /** What a notification says when a message is not to be shown at all. */
    const val HIDDEN = "New message"

    /** What a notification says when there is no sender to name. */
    const val UNKNOWN_SENDER = "Somebody"

    /**
     * The body of a notification.
     *
     * @param mode how much the person asked to be shown.
     * @param senderName who wrote the message, or null when there is nobody to name.
     * @param messageText the message, or null when it has no words - a photo, say.
     */
    fun content(mode: NotificationPreview, senderName: String?, messageText: String?): String {
        val sender = senderName?.trim()?.takeIf { it.isNotEmpty() }
        val text = messageText?.trim()?.takeIf { it.isNotEmpty() }
        return when (mode) {
            // A message with no words still says what it is: the words are somebody else's job.
            NotificationPreview.FULL -> text ?: HIDDEN
            NotificationPreview.SENDER -> sender ?: UNKNOWN_SENDER
            NotificationPreview.NONE -> HIDDEN
        }
    }

    /** True when the notification will show the message to anybody who can see the screen. */
    fun revealsMessage(mode: NotificationPreview, messageText: String?): Boolean =
        mode == NotificationPreview.FULL && !messageText.isNullOrBlank()
}
