package com.telefarm.core.media

/** Whether a recording is worth sending. */
sealed interface RecordingDecision {

    /**
     * Too short to be a message.
     *
     * @param shortestMs how long it has to be.
     */
    data class TooShort(val shortestMs: Long) : RecordingDecision

    /**
     * Long enough to send.
     *
     * @param seconds how long it ran, in whole seconds.
     */
    data class Ready(val seconds: Int) : RecordingDecision
}

/**
 * Recording a voice message.
 *
 * A recording is the one message that cannot be read before it is sent, so the decision about
 * whether to send it is made here rather than left to the person holding the phone: a recording
 * under a second is a finger that slipped, and sending one is noise at the other end that cannot
 * be taken back.
 *
 * The elapsed time rounds **down**. A recording that ran for 1.9 seconds is a 1 second recording;
 * calling it 2 would say it is longer than it was, and length is the only thing the other person
 * has to go on before they press play.
 */
object VoiceRecording {

    /** Shorter than this is a slip of the finger rather than a message. */
    const val MINIMUM_MS = 1_000L

    private const val MS_PER_SECOND = 1_000L

    /** What a recording that ran for [elapsedMs] is worth. */
    fun decide(elapsedMs: Long): RecordingDecision =
        if (elapsedMs < MINIMUM_MS) {
            RecordingDecision.TooShort(MINIMUM_MS)
        } else {
            RecordingDecision.Ready(seconds(elapsedMs))
        }

    /** Whole seconds of a recording, rounded down. */
    fun seconds(elapsedMs: Long): Int =
        if (elapsedMs <= 0L) 0 else (elapsedMs / MS_PER_SECOND).toInt()

    /**
     * What to call the recording.
     *
     * Recordings have no name of their own, so they are named from when they were made - which is
     * also what tells two recordings taken a minute apart apart.
     */
    fun fileName(unique: Long): String = "voice-$unique.m4a"
}
