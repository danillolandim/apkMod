package com.seupainel.player.playlist

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamShortEpgTest {

    // JSON de exemplo inventado; títulos em base64 como os painéis Xtream costumam mandar.
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun item(title: String, start: Any, stop: Any, extra: String = "") =
        """{"title":"$title","start_timestamp":$start,"stop_timestamp":$stop$extra}"""

    private fun listings(vararg items: String) = """{"epg_listings":[${items.joinToString(",")}]}"""

    @Test
    fun `titulo em base64 e decodificado e o tempo vai para milissegundos`() {
        val json = listings(
            item(b64("Jornal da Manhã"), 1_700_000_000, 1_700_003_600, ",\"description\":\"${b64("Notícias do dia")}\""),
        )
        val p = XtreamApi.parseShortEpg(json).single()
        assertEquals("Jornal da Manhã", p.title)
        assertEquals(1_700_000_000_000L, p.start)
        assertEquals(1_700_003_600_000L, p.stop)
    }

    @Test
    fun `titulo que nao e base64 passa como veio`() {
        val json = listings(item("Morning News", 1_700_000_000, 1_700_003_600))
        assertEquals("Morning News", XtreamApi.parseShortEpg(json).single().title)
    }

    @Test
    fun `palavra solta com cara de base64 mas sem texto valido por baixo continua como veio`() {
        listOf("Rede", "Jornal", "Cinema", "Novela", "Esporte", "Hoje", "Show", "AAAA", "Cine").forEach { w ->
            assertEquals(w, XtreamApi.parseShortEpg(listings(item(w, 1_700_000_000, 1_700_003_600))).single().title)
        }
    }

    @Test
    fun `decodifica base64 com acentos e recusa o que nao e texto`() {
        assertEquals("Novela das Nove", XtreamApi.decodeBase64Text(b64("Novela das Nove")))
        assertEquals("Ação", XtreamApi.decodeBase64Text(b64("Ação")))
        assertEquals("ab", XtreamApi.decodeBase64Text("YWI="))
        assertNull(XtreamApi.decodeBase64Text("YWI"))              // sem o "=": é só uma palavra
        assertNull(XtreamApi.decodeBase64Text("com espaço"))
        assertNull(XtreamApi.decodeBase64Text("Notícias"))
        assertNull(XtreamApi.decodeBase64Text(""))
        assertNull(XtreamApi.decodeBase64Text("A"))                // tamanho impossível
        assertNull(XtreamApi.decodeBase64Text("OK"))
        assertNull(XtreamApi.decodeBase64Text("/+8="))              // bytes que não são UTF-8
        assertNull(XtreamApi.decodeBase64Text(b64("linha\ncom quebra")))   // título de programa não tem caractere de controle
    }

    @Test
    fun `a decisao de decodificar vale para a resposta toda`() {
        // Um painel manda tudo em base64 ou nada: se um título não decodifica, nenhum é decodificado.
        val json = listings(item(b64("Jornal"), 1_700_000_000, 1_700_003_600), item("Rede", 1_700_003_600, 1_700_007_200))
        assertEquals(listOf(b64("Jornal"), "Rede"), XtreamApi.parseShortEpg(json).map { it.title })
    }

    @Test
    fun `todos os titulos em base64 sao decodificados`() {
        val json = listings(item(b64("Jornal"), 1_700_000_000, 1_700_003_600), item(b64("Rede"), 1_700_003_600, 1_700_007_200))
        assertEquals(listOf("Jornal", "Rede"), XtreamApi.parseShortEpg(json).map { it.title })
    }

    @Test
    fun `timestamps como texto tambem valem`() {
        val json = listings(item("A", "\"1700000000\"", "\"1700003600\""))
        val p = XtreamApi.parseShortEpg(json).single()
        assertEquals(1_700_000_000_000L, p.start)
    }

    @Test
    fun `timestamp que ja vem em milissegundos nao e multiplicado de novo`() {
        val p = XtreamApi.parseShortEpg(listings(item("A", 1_700_000_000_000L, 1_700_003_600_000L))).single()
        assertEquals(1_700_000_000_000L, p.start)
        assertEquals(1_700_003_600_000L, p.stop)
    }

    @Test
    fun `entrada incompleta e descartada e as boas ficam`() {
        val json = listings(
            """{"start_timestamp":1700000000,"stop_timestamp":1700003600}""",          // sem título
            """{"title":"B"}""",                                                          // sem horário
            item("C", 1_700_003_600, 1_700_000_000),                                      // termina antes de começar
            item("D", 0, 1_700_000_000),                                                  // início inválido
            item("   ", 1_700_000_000, 1_700_003_600),                                    // título em branco
            """{"title":null,"start_timestamp":1700000000,"stop_timestamp":1700003600}""", // título nulo
            item("OK", 1_700_000_000, 1_700_003_600),
        )
        assertEquals(listOf("OK"), XtreamApi.parseShortEpg(json).map { it.title })
    }

    @Test
    fun `resultado vem ordenado pelo inicio`() {
        val json = listings(
            item("Depois", 1_700_007_200, 1_700_010_800),
            item("Antes", 1_700_000_000, 1_700_003_600),
        )
        assertEquals(listOf("Antes", "Depois"), XtreamApi.parseShortEpg(json).map { it.title })
    }

    @Test
    fun `lista vazia, chave ausente ou nula nao dao programas`() {
        assertTrue(XtreamApi.parseShortEpg("""{"epg_listings":[]}""").isEmpty())
        assertTrue(XtreamApi.parseShortEpg("{}").isEmpty())
        assertTrue(XtreamApi.parseShortEpg("""{"epg_listings":null}""").isEmpty())
        assertTrue(XtreamApi.parseShortEpg("""{"epg_listings":"erro"}""").isEmpty())
    }

    @Test
    fun `resposta que nao e json nao quebra`() {
        listOf("", "   ", "<html>Forbidden</html>", "Forbidden", "null", "[]", "{", """{"epg_listings":[{""", "123").forEach {
            assertTrue("entrada: $it", XtreamApi.parseShortEpg(it).isEmpty())
        }
    }

    @Test
    fun `aceita lista solta, objeto indexado e BOM no comeco`() {
        val one = item("A", 1_700_000_000, 1_700_003_600)
        assertEquals(listOf("A"), XtreamApi.parseShortEpg("[$one]").map { it.title })
        assertEquals(listOf("A"), XtreamApi.parseShortEpg("""{"epg_listings":{"0":$one}}""").map { it.title })
        assertEquals(listOf("A"), XtreamApi.parseShortEpg("﻿" + listings(one)).map { it.title })
    }

    @Test
    fun `ignora elementos que nao sao objetos dentro da lista`() {
        val json = """{"epg_listings":[1,"x",null,${item("A", 1_700_000_000, 1_700_003_600)}]}"""
        assertEquals(listOf("A"), XtreamApi.parseShortEpg(json).map { it.title })
    }

    @Test
    fun `monta a url do short epg com o id do canal e o limite`() {
        val acc = XtreamApi.Account("http://h:80", "u", "p")
        assertEquals(
            "http://h:80/player_api.php?username=u&password=p&action=get_short_epg&stream_id=123&limit=6",
            XtreamApi.shortEpgUrl(acc, 123, 6),
        )
    }
}
