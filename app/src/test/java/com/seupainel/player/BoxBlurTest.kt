package com.seupainel.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun red(px: Int) = (px shr 16) and 0xFF

    @Test
    fun `imagem de uma cor so continua igual`() {
        val px = IntArray(6 * 6) { gray(90) }
        BoxBlur.blur(px, 6, 6, radius = 2, passes = 2)
        assertTrue(px.all { it == gray(90) })
    }

    @Test
    fun `ponto claro espalha para os vizinhos e perde brilho no centro`() {
        val w = 9
        val px = IntArray(w * w) { gray(0) }
        px[4 * w + 4] = gray(255)
        BoxBlur.blur(px, w, w, radius = 2, passes = 1)
        assertTrue(red(px[4 * w + 4]) in 1..254)
        assertTrue(red(px[4 * w + 5]) > 0)
        assertEquals(0, red(px[0]))   // longe do ponto nada muda
    }

    @Test
    fun `mantem o alfa e nao estoura os canais`() {
        val px = IntArray(16) { (0x80 shl 24) or 0x00FF00FF }
        BoxBlur.blur(px, 4, 4, radius = 1, passes = 3)
        assertTrue(px.all { (it ushr 24) == 0x80 })
        assertTrue(px.all { ((it shr 16) and 0xFF) in 0..255 })
    }

    @Test
    fun `raio zero ou tamanho invalido nao mexe`() {
        val px = IntArray(4) { gray(it * 50) }
        val copy = px.copyOf()
        BoxBlur.blur(px, 2, 2, radius = 0, passes = 2)
        assertTrue(px.contentEquals(copy))
        BoxBlur.blur(px, 3, 3, radius = 2, passes = 2)   // w*h não bate com o vetor
        assertTrue(px.contentEquals(copy))
    }
}
