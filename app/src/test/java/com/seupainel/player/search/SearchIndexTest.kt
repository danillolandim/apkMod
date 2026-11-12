package com.seupainel.player.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {

    private fun doc(title: String, rating: Double = 0.0, added: Long = 0L) = SearchDoc(title, rating, added)

    private fun titles(docs: List<SearchDoc>, query: String, limit: Int = 40): List<String> {
        val r = SearchIndex(docs).search(query, limit) ?: error("cancelada")
        return r.top.map { docs[it].title }
    }

    // ---------- casamento ----------

    @Test
    fun `ignora acento e maiuscula na consulta e no titulo`() {
        val docs = listOf(doc("Ação Explosiva"), doc("Outro filme"))
        assertEquals(listOf("Ação Explosiva"), titles(docs, "ACAO"))
        assertEquals(listOf("Ação Explosiva"), titles(docs, "ação"))
    }

    @Test
    fun `varias palavras exigem todas, em qualquer ordem`() {
        val docs = listOf(doc("Homem de Ferro"), doc("Homem Aranha"), doc("Ferro Velho"))
        assertEquals(listOf("Homem de Ferro"), titles(docs, "ferro homem"))
        assertEquals(listOf("Homem Aranha"), titles(docs, "homem ara"))
    }

    @Test
    fun `sem palavra casando nao devolve nada`() {
        val docs = listOf(doc("Matrix"))
        val r = SearchIndex(docs).search("zzz", 40) ?: error("cancelada")
        assertEquals(0, r.total)
        assertTrue(r.top.isEmpty())
    }

    @Test
    fun `consulta vazia nao devolve nada`() {
        val r = SearchIndex(listOf(doc("Matrix"))).search("   ", 40) ?: error("cancelada")
        assertEquals(0, r.total)
    }

    @Test
    fun `contem no meio da palavra tambem casa`() {
        assertEquals(listOf("Avatar"), titles(listOf(doc("Avatar")), "tar"))
    }

    // ---------- ordem ----------

    @Test
    fun `comeca com o texto vem antes de palavra que comeca e antes de contem`() {
        val docs = listOf(
            doc("O Grande Matrix"),   // uma palavra começa com o texto
            doc("Super Matrixes"),    // idem
            doc("Matrix"),            // o título começa com o texto
            doc("Dismatrix"),         // só contém, no meio da palavra
        )
        assertEquals(
            listOf("Matrix", "O Grande Matrix", "Super Matrixes", "Dismatrix"),
            titles(docs, "matrix"),
        )
    }

    @Test
    fun `no mesmo nivel desempata por nota e depois por mais recente`() {
        val docs = listOf(
            doc("Alfa um", rating = 6.0, added = 10),
            doc("Alfa dois", rating = 8.0, added = 5),
            doc("Alfa tres", rating = 8.0, added = 50),
        )
        assertEquals(listOf("Alfa tres", "Alfa dois", "Alfa um"), titles(docs, "alfa"))
    }

    @Test
    fun `empate total fica em ordem alfabetica e estavel`() {
        val docs = listOf(doc("Zeta casa"), doc("Zeta bola"), doc("Zeta anel"))
        assertEquals(listOf("Zeta anel", "Zeta bola", "Zeta casa"), titles(docs, "zeta"))
    }

    @Test
    fun `consulta de varias palavras colada no inicio ganha de palavras soltas`() {
        val docs = listOf(
            doc("Ferro de Homem", rating = 9.0),
            doc("Homem de Ferro", rating = 1.0),
            doc("Homem Aranha de Ferro", rating = 5.0),
        )
        // "homem de" começa o título de [1]; nos outros as palavras só aparecem soltas
        assertEquals("Homem de Ferro", titles(docs, "homem de").first())
    }

    // ---------- limite e contagem ----------

    @Test
    fun `limita os cartoes mas conta todos os resultados`() {
        val docs = (1..100).map { doc("Filme $it", rating = it.toDouble()) }
        val r = SearchIndex(docs).search("filme", 40) ?: error("cancelada")
        assertEquals(100, r.total)
        assertEquals(40, r.top.size)
        assertEquals("Filme 100", docs[r.top.first()].title)   // maior nota primeiro
    }

    @Test
    fun `devolve indices da lista original`() {
        val docs = listOf(doc("Nada"), doc("Matrix"), doc("Nada de novo"))
        val r = SearchIndex(docs).search("matrix", 40) ?: error("cancelada")
        assertEquals(listOf(1), r.top)
    }

    // ---------- cancelamento ----------

    @Test
    fun `busca cancelada devolve null`() {
        val docs = (1..10_000).map { doc("Filme $it") }
        assertNull(SearchIndex(docs).search("filme", 40, cancelled = { true }))
    }

    // ---------- nível de ranking isolado ----------

    @Test
    fun `rank`() {
        fun r(title: String, q: String): Int {
            val tokens = SearchText.tokens(q)
            return SearchIndex.rank(SearchText.normalize(title), tokens.joinToString(" "), tokens)
        }
        assertEquals(0, r("Matrix Reloaded", "matrix"))
        assertEquals(0, r("Homem de Ferro", "homem de"))
        assertEquals(1, r("O Homem de Ferro", "homem"))
        assertEquals(1, r("Ferro Homem", "homem fer"))
        assertEquals(2, r("Dismatrix", "matrix"))
        assertEquals(-1, r("Matrix", "neo"))
        assertEquals(-1, r("Homem Aranha", "homem ferro"))
    }
}
