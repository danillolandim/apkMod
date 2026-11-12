package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveZapperTest {

    @Test
    fun `next e prev dao a volta nas pontas`() {
        val z = LiveZapper(size = 3)
        assertEquals(0, z.current)
        assertEquals(2, z.prev())          // do primeiro vai para o último
        assertEquals(0, z.next())          // do último volta ao primeiro
        assertEquals(1, z.next())
        assertEquals(1, z.current)
    }

    @Test
    fun `comeca no indice pedido e ajusta indice fora da faixa`() {
        assertEquals(4, LiveZapper(size = 10, start = 4).current)
        assertEquals(9, LiveZapper(size = 10, start = 99).current)
        assertEquals(0, LiveZapper(size = 10, start = -5).current)
    }

    @Test
    fun `jumpTo so aceita indice valido e nao mexe no atual quando recusa`() {
        val z = LiveZapper(size = 5, start = 2)
        assertEquals(4, z.jumpTo(4))
        assertNull(z.jumpTo(5))
        assertNull(z.jumpTo(-1))
        assertEquals(4, z.current)
    }

    @Test
    fun `byNumber usa indice mais um e nao move o canal atual`() {
        val z = LiveZapper(size = 5, start = 2)
        assertEquals(0, z.byNumber(1))
        assertEquals(4, z.byNumber(5))
        assertNull(z.byNumber(0))
        assertNull(z.byNumber(6))
        assertNull(z.byNumber(-3))
        assertEquals(2, z.current)
    }

    @Test
    fun `last sem historico nao faz nada`() {
        val z = LiveZapper(size = 5, start = 1)
        assertNull(z.last())
        assertEquals(1, z.current)
    }

    @Test
    fun `last alterna entre os dois ultimos canais sintonizados`() {
        val z = LiveZapper(size = 10, start = 5)
        z.next(); z.commit()               // sintonizou 6, veio do 5
        assertEquals(5, z.last())
        z.commit()
        assertEquals(6, z.last())
        z.commit()
        assertEquals(5, z.last())
    }

    @Test
    fun `passar por varios canais sem sintonizar nao polui o last`() {
        val z = LiveZapper(size = 20, start = 0)
        repeat(8) { z.next() }             // segurou CH+: nenhum desses chegou a sintonizar
        z.commit()                         // parou no 8
        assertEquals(0, z.last())          // volta ao que estava vendo, nao ao 7
    }

    @Test
    fun `last com troca ainda pendente trata o canal pendente como assistido`() {
        val z = LiveZapper(size = 10, start = 0)
        z.next(); z.commit()               // vendo o 1, antes via o 0
        z.next()                           // apertou CH+ (2), ainda sem sintonizar
        assertEquals(1, z.last())          // volta ao que de fato estava vendo
    }

    @Test
    fun `commit sem mudanca nao altera o historico`() {
        val z = LiveZapper(size = 4, start = 2)
        z.commit(); z.commit()
        assertNull(z.last())
    }

    @Test
    fun `lista de um item fica sempre no mesmo canal`() {
        val z = LiveZapper(size = 1)
        assertEquals(0, z.next())
        assertEquals(0, z.prev())
        assertEquals(0, z.byNumber(1))
        assertNull(z.byNumber(2))
        z.commit()
        assertNull(z.last())
        assertEquals(0, z.current)
    }

    @Test
    fun `lista vazia nao tem canal atual e recusa tudo`() {
        val z = LiveZapper(size = 0)
        assertEquals(-1, z.current)
        assertNull(z.next())
        assertNull(z.prev())
        assertNull(z.jumpTo(0))
        assertNull(z.byNumber(1))
        z.commit()
        assertNull(z.last())
    }
}
