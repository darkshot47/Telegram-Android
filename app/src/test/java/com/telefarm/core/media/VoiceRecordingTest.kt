package com.telefarm.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording a voice message. */
class VoiceRecordingTest {

    @Test
    fun `a recording under a second is a slip of the finger`() {
        val decision = VoiceRecording.decide(999)
        assertTrue(decision is RecordingDecision.TooShort)
        assertEquals(VoiceRecording.MINIMUM_MS, (decision as RecordingDecision.TooShort).shortestMs)
    }

    @Test
    fun `nothing recorded at all is too short`() {
        assertTrue(VoiceRecording.decide(0) is RecordingDecision.TooShort)
    }

    @Test
    fun `a negative elapsed time is too short rather than an error`() {
        assertTrue(VoiceRecording.decide(-500) is RecordingDecision.TooShort)
    }

    @Test
    fun `exactly a second is a message`() {
        val decision = VoiceRecording.decide(1_000)
        assertTrue(decision is RecordingDecision.Ready)
        assertEquals(1, (decision as RecordingDecision.Ready).seconds)
    }

    @Test
    fun `a long recording is a message`() {
        val decision = VoiceRecording.decide(64_000)
        assertEquals(64, (decision as RecordingDecision.Ready).seconds)
    }

    @Test
    fun `the length rounds down, because a recording is not longer than it was`() {
        assertEquals(1, VoiceRecording.seconds(1_900))
        assertEquals(1, VoiceRecording.seconds(1_001))
        assertEquals(2, VoiceRecording.seconds(2_000))
    }

    @Test
    fun `nothing recorded is no seconds`() {
        assertEquals(0, VoiceRecording.seconds(0))
        assertEquals(0, VoiceRecording.seconds(-1))
    }

    @Test
    fun `a minute of recording is sixty seconds`() {
        assertEquals(60, VoiceRecording.seconds(60_000))
    }

    @Test
    fun `two recordings taken apart are named apart`() {
        assertTrue(VoiceRecording.fileName(1) != VoiceRecording.fileName(2))
    }

    @Test
    fun `a recording is named as a sound file`() {
        assertTrue(VoiceRecording.fileName(7).endsWith(".m4a"))
        assertTrue(VoiceRecording.fileName(7).startsWith("voice-"))
    }

    @Test
    fun `every length at or past the minimum is ready`() {
        for (elapsed in listOf(1_000L, 1_500L, 30_000L, 600_000L)) {
            assertTrue("$elapsed was not ready", VoiceRecording.decide(elapsed) is RecordingDecision.Ready)
        }
    }

    @Test
    fun `every length under the minimum is too short`() {
        for (elapsed in listOf(-1L, 0L, 1L, 500L, 999L)) {
            assertTrue("$elapsed was not too short", VoiceRecording.decide(elapsed) is RecordingDecision.TooShort)
        }
    }
}
