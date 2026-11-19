package com.seupainel.player

import com.seupainel.player.VodRow.Button
import org.junit.Assert.assertEquals
import org.junit.Test

class VodRowTest {

    private val withNext = VodRow.buttons(hasNext = true)
    private val noNext = VodRow.buttons(hasNext = false)

    // ---------- quais botões existem ----------

    @Test
    fun `com proximo item a fileira tem quatro botoes na ordem do desenho`() {
        assertEquals(listOf(Button.PLAY, Button.AUDIO, Button.NEXT, Button.MORE), withNext)
    }

    @Test
    fun `sem proximo item o botao de proximo episodio nao existe`() {
        assertEquals(listOf(Button.PLAY, Button.AUDIO, Button.MORE), noNext)
    }

    // ---------- andar com as setas ----------

    @Test
    fun `direita anda um botao e esquerda volta um`() {
        assertEquals(1, VodRow.move(0, +1, withNext.size))
        assertEquals(2, VodRow.move(1, +1, withNext.size))
        assertEquals(1, VodRow.move(2, -1, withNext.size))
    }

    @Test
    fun `para na ponta direita sem dar a volta`() {
        assertEquals(3, VodRow.move(3, +1, withNext.size))
        assertEquals(2, VodRow.move(2, +1, noNext.size))
    }

    @Test
    fun `para na ponta esquerda sem dar a volta`() {
        assertEquals(0, VodRow.move(0, -1, withNext.size))
    }

    @Test
    fun `so o sinal da direcao vale`() {
        assertEquals(1, VodRow.move(0, +5, withNext.size))
        assertEquals(0, VodRow.move(1, -5, withNext.size))
    }

    @Test
    fun `no modo linha as setas da fileira nao fazem nada`() {
        assertEquals(VodRow.LINE, VodRow.move(VodRow.LINE, +1, withNext.size))
        assertEquals(VodRow.LINE, VodRow.move(VodRow.LINE, -1, withNext.size))
    }

    @Test
    fun `fileira vazia devolve o modo linha`() {
        assertEquals(VodRow.LINE, VodRow.move(0, +1, 0))
    }

    @Test
    fun `indice maior que a fileira volta para a ultima ponta`() {
        assertEquals(2, VodRow.move(7, +1, noNext.size))
    }

    // ---------- a lista de botões mudou ----------

    @Test
    fun `quando o proximo episodio aparece o botao escolhido continua o mesmo`() {
        // Mais opções era o índice 2 e passa a ser o 3
        assertEquals(3, VodRow.remap(noNext, 2, withNext))
        assertEquals(0, VodRow.remap(noNext, 0, withNext))
        assertEquals(1, VodRow.remap(noNext, 1, withNext))
    }

    @Test
    fun `quando o proximo episodio some os botoes depois dele andam para tras`() {
        assertEquals(2, VodRow.remap(withNext, 3, noNext))   // Mais opções
        assertEquals(0, VodRow.remap(withNext, 0, noNext))
        assertEquals(1, VodRow.remap(withNext, 1, noNext))
    }

    @Test
    fun `se o escolhido era o proximo episodio e ele some fica no indice mais proximo`() {
        assertEquals(2, VodRow.remap(withNext, 2, noNext))
        assertEquals(Button.MORE, noNext[VodRow.remap(withNext, 2, noNext)])
    }

    @Test
    fun `modo linha continua modo linha quando a lista muda`() {
        assertEquals(VodRow.LINE, VodRow.remap(noNext, VodRow.LINE, withNext))
        assertEquals(VodRow.LINE, VodRow.remap(withNext, VodRow.LINE, noNext))
    }

    @Test
    fun `lista nova vazia devolve o modo linha`() {
        assertEquals(VodRow.LINE, VodRow.remap(withNext, 1, emptyList()))
    }
}
