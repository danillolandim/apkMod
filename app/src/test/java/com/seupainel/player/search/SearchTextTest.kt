package com.seupainel.player.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun `tira acentos e maiusculas`() {
        assertEquals("acao", SearchText.normalize("Ação"))
        assertEquals("pao de acucar", SearchText.normalize("Pão de Açúcar"))
        assertEquals("epico", SearchText.normalize("ÉPICO"))
    }

    @Test
    fun `pontuacao vira espaco e espacos se juntam`() {
        assertEquals("spider man homecoming", SearchText.normalize("  Spider-Man:  Homecoming "))
        assertEquals("homem aranha", SearchText.normalize("Homem-Aranha"))
        assertEquals("a b", SearchText.normalize("a / b"))
    }

    @Test
    fun `apostrofo e ponto colam em vez de separar`() {
        assertEquals("greys anatomy", SearchText.normalize("Grey's Anatomy"))
        assertEquals("swat", SearchText.normalize("S.W.A.T."))
        assertEquals("mr robot", SearchText.normalize("Mr. Robot"))
    }

    @Test
    fun `numeros sao mantidos`() {
        assertEquals("mission impossible 7", SearchText.normalize("Mission: Impossible 7"))
        assertEquals("2012", SearchText.normalize("2012"))
    }

    @Test
    fun `texto vazio ou so pontuacao vira vazio`() {
        assertEquals("", SearchText.normalize(""))
        assertEquals("", SearchText.normalize("  --  "))
    }

    @Test
    fun `tokens separam as palavras ja normalizadas`() {
        assertEquals(listOf("homem", "aranha"), SearchText.tokens("  HOMEM   Aranha "))
        assertTrue(SearchText.tokens("   ").isEmpty())
    }
}
