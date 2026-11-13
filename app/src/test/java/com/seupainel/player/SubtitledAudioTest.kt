package com.seupainel.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitledAudioTest {

    @Test fun `dual audio file playing portuguese switches to the original`() {
        assertEquals(1, SubtitledAudio.originalTrack(listOf("pt", "en"), selected = 0))
        assertEquals(0, SubtitledAudio.originalTrack(listOf("en", "pt-BR"), selected = 1))
    }

    @Test fun `already original, single track or only portuguese stays as it is`() {
        assertNull(SubtitledAudio.originalTrack(listOf("pt", "en"), selected = 1))
        assertNull(SubtitledAudio.originalTrack(listOf("pt"), selected = 0))
        assertNull(SubtitledAudio.originalTrack(listOf("pt", "pt-BR"), selected = 0))
        assertNull(SubtitledAudio.originalTrack(listOf("pt", "en"), selected = -1))
        assertNull(SubtitledAudio.originalTrack(emptyList(), selected = 0))
    }

    @Test fun `declared language wins over an unknown one, unknown is used when it is all there is`() {
        assertEquals(2, SubtitledAudio.originalTrack(listOf("pt", null, "ja"), selected = 0))
        assertEquals(1, SubtitledAudio.originalTrack(listOf("pt", "und"), selected = 0))
        assertEquals(1, SubtitledAudio.originalTrack(listOf("por", null), selected = 0))
    }

    @Test fun `portuguese subtitle is turned on only when none is on`() {
        assertEquals(1, SubtitledAudio.portugueseSubtitle(listOf("en", "pt-BR"), selected = -1))
        assertNull(SubtitledAudio.portugueseSubtitle(listOf("en", "pt"), selected = 0))
        assertNull(SubtitledAudio.portugueseSubtitle(listOf("en", "es"), selected = -1))
    }
}
