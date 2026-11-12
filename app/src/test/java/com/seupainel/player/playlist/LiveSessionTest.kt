package com.seupainel.player.playlist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSessionTest {

    private fun ch(name: String, vararg urls: String) = LiveChannel(
        name = name,
        url = urls.first(),
        logo = "",
        tvgId = "",
        apiId = 0L,
        group = "",
        userAgent = null,
        referer = null,
        variantUrls = if (urls.size > 1) urls.toList() else emptyList(),
        variantLabels = emptyList(),
    )

    @After
    fun limpa() = LiveSession.clear()

    @Test
    fun `sessao vazia nao corresponde a nenhuma url`() {
        assertNull(LiveSession.indexOf("http://x/1"))
    }

    @Test
    fun `corresponde pelo canal escolhido ou por uma das qualidades dele`() {
        LiveSession.set(listOf(ch("A", "http://x/a"), ch("B", "http://x/b_fhd", "http://x/b_fhd", "http://x/b_sd")), index = 1)
        assertEquals(1, LiveSession.indexOf("http://x/b_fhd"))
        assertEquals(1, LiveSession.indexOf("http://x/b_sd"))
    }

    @Test
    fun `canal que nao bate com o indice escolhido derruba a sessao para o modo de canal unico`() {
        LiveSession.set(listOf(ch("A", "http://x/a"), ch("B", "http://x/b")), index = 0)
        assertNull(LiveSession.indexOf("http://x/b"))
    }

    @Test
    fun `indice fora da lista e tratado como sessao invalida`() {
        LiveSession.set(listOf(ch("A", "http://x/a")), index = 7)
        assertNull(LiveSession.indexOf("http://x/a"))
    }

    @Test
    fun `clear esvazia a sessao`() {
        LiveSession.set(listOf(ch("A", "http://x/a")), index = 0)
        LiveSession.clear()
        assertEquals(0, LiveSession.channels.size)
    }
}
