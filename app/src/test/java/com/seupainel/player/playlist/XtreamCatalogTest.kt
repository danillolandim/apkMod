package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamCatalogTest {

    private val acc = XtreamApi.Account("http://h:8080", "usr", "pwd")

    private fun live(url: String) = M3uParser.Channel(name = "Canal", url = url, group = "g", duration = -1)

    @Test
    fun `copia o formato com live e extensao ts`() {
        val raw = listOf(live("http://h:8080/live/usr/pwd/101.ts"))
        assertEquals("http://h:8080/live/usr/pwd/{id}.ts", XtreamCatalog.liveTemplate(acc, raw))
    }

    @Test
    fun `copia o formato sem live e sem extensao`() {
        val raw = listOf(live("http://h:8080/usr/pwd/101"))
        assertEquals("http://h:8080/usr/pwd/{id}", XtreamCatalog.liveTemplate(acc, raw))
    }

    @Test
    fun `copia a extensao m3u8`() {
        val raw = listOf(live("http://h:8080/live/usr/pwd/101.m3u8"))
        assertEquals("http://h:8080/live/usr/pwd/{id}.m3u8", XtreamCatalog.liveTemplate(acc, raw))
    }

    @Test
    fun `ignora filmes e usa o padrao quando nao ha canal ao vivo`() {
        val movie = M3uParser.Channel(name = "Filme", url = "http://h:8080/movie/usr/pwd/9.mp4", group = "g")
        assertEquals("http://h:8080/live/usr/pwd/{id}.ts", XtreamCatalog.liveTemplate(acc, listOf(movie)))
        assertEquals("http://h:8080/live/usr/pwd/{id}.ts", XtreamCatalog.liveTemplate(acc, emptyList()))
    }
}
