package com.seupainel.player.search

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {

    @Test
    fun `termo novo vai para o inicio`() {
        assertEquals(listOf("duna", "matrix"), RecentSearches.add(listOf("matrix"), "duna"))
    }

    @Test
    fun `repetido sobe e nao duplica, ignorando caixa e acento`() {
        assertEquals(listOf("Ação", "matrix"), RecentSearches.add(listOf("matrix", "acao"), "Ação"))
    }

    @Test
    fun `guarda no maximo oito`() {
        var l = emptyList<String>()
        for (i in 1..12) l = RecentSearches.add(l, "termo $i")
        assertEquals(8, l.size)
        assertEquals("termo 12", l.first())
        assertEquals("termo 5", l.last())
    }

    @Test
    fun `termo curto demais ou em branco e ignorado`() {
        assertEquals(listOf("a b"), RecentSearches.add(listOf("a b"), "x"))
        assertEquals(listOf("a b"), RecentSearches.add(listOf("a b"), "   "))
    }

    @Test
    fun `espacos sobrando sao aparados`() {
        assertEquals(listOf("dark house"), RecentSearches.add(emptyList(), "  dark   house "))
    }

    @Test
    fun `remove um termo`() {
        assertEquals(listOf("b"), RecentSearches.remove(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), RecentSearches.remove(listOf("a", "b"), "z"))
    }

    @Test
    fun `serializa e le de volta sem perder termos com caracteres especiais`() {
        val l = listOf("duna", "grey's \"anatomy\"", "linha\nnova")
        assertEquals(l, RecentSearches.decode(RecentSearches.encode(l)))
    }

    @Test
    fun `texto salvo corrompido vira lista vazia`() {
        assertEquals(emptyList<String>(), RecentSearches.decode("isto nao e json"))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
    }
}
