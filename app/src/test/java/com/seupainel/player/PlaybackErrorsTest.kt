package com.seupainel.player

import com.seupainel.player.PlaybackErrors.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {

    @Test
    fun `classifica por codigo http`() {
        assertEquals(Kind.NOT_FOUND, PlaybackErrors.kindOf(404, false, false, false))
        assertEquals(Kind.NOT_FOUND, PlaybackErrors.kindOf(410, false, false, false))
        assertEquals(Kind.DENIED, PlaybackErrors.kindOf(403, false, false, false))
        assertEquals(Kind.DENIED, PlaybackErrors.kindOf(401, false, false, false))
        assertEquals(Kind.SERVER_BUSY, PlaybackErrors.kindOf(503, false, false, false))
        assertEquals(Kind.SERVER_BUSY, PlaybackErrors.kindOf(429, false, false, false))
        assertEquals(Kind.OTHER, PlaybackErrors.kindOf(416, false, false, false))
    }

    @Test
    fun `formato nao suportado e arquivo inexistente ganham prioridade sobre rede`() {
        assertEquals(Kind.UNSUPPORTED, PlaybackErrors.kindOf(null, true, false, true))
        assertEquals(Kind.NOT_FOUND, PlaybackErrors.kindOf(null, false, true, true))
        assertEquals(Kind.NETWORK, PlaybackErrors.kindOf(null, false, false, true))
        assertEquals(Kind.OTHER, PlaybackErrors.kindOf(null, false, false, false))
    }

    @Test
    fun `erro definitivo nao repete`() {
        assertNull(PlaybackErrors.retryDelayMs(Kind.NOT_FOUND, 1, false))
        assertNull(PlaybackErrors.retryDelayMs(Kind.UNSUPPORTED, 1, false))
        assertNull(PlaybackErrors.retryDelayMs(Kind.DENIED, 2, false))
    }

    @Test
    fun `acesso negado tem uma unica tentativa extra`() {
        assertEquals(1_000L, PlaybackErrors.retryDelayMs(Kind.DENIED, 1, false))
    }

    @Test
    fun `rede repete pouco em filme e mais em canal ao vivo`() {
        assertEquals(600L, PlaybackErrors.retryDelayMs(Kind.NETWORK, 1, false))
        assertEquals(1_200L, PlaybackErrors.retryDelayMs(Kind.NETWORK, 2, false))
        assertNull(PlaybackErrors.retryDelayMs(Kind.NETWORK, 3, false))
        assertEquals(2_500L, PlaybackErrors.retryDelayMs(Kind.NETWORK, 5, true))
        assertNull(PlaybackErrors.retryDelayMs(Kind.NETWORK, 6, true))
    }

    @Test
    fun `servidor ocupado espera mais e limita a espera`() {
        assertEquals(800L, PlaybackErrors.retryDelayMs(Kind.SERVER_BUSY, 1, false))
        assertEquals(2_400L, PlaybackErrors.retryDelayMs(Kind.SERVER_BUSY, 3, false))
        assertNull(PlaybackErrors.retryDelayMs(Kind.SERVER_BUSY, 4, false))
    }

    @Test
    fun `so rede e servidor ocupado sao passageiros`() {
        assertTrue(PlaybackErrors.isTransient(Kind.NETWORK))
        assertTrue(PlaybackErrors.isTransient(Kind.SERVER_BUSY))
        assertFalse(PlaybackErrors.isTransient(Kind.DENIED))
        assertFalse(PlaybackErrors.isTransient(Kind.NOT_FOUND))
        assertFalse(PlaybackErrors.isTransient(Kind.UNSUPPORTED))
    }

    @Test
    fun `mensagem inclui o codigo http quando existe`() {
        assertTrue(PlaybackErrors.message(Kind.NOT_FOUND, 404).contains("HTTP 404"))
        assertFalse(PlaybackErrors.message(Kind.NETWORK, null).contains("HTTP"))
    }
}
