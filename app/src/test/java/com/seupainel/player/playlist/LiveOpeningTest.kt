package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOpeningTest {

    private fun ch(name: String, group: String, kind: String? = null, url: String = "http://x/$name", apiId: Long = 0L) =
        M3uParser.Channel(name = name, url = url, group = group, duration = -1, kind = kind, apiId = apiId)

    private fun api(vararg channels: M3uParser.Channel, order: List<String> = emptyList()) =
        XtreamCatalog.Section(channels.toList(), order)

    private fun assemble(
        useXtream: Boolean = true,
        api: XtreamCatalog.Section? = null,
        note: String? = null,
        raw: List<M3uParser.Channel>? = null,
        preloadDone: Boolean = true,
        favorites: Set<String> = emptySet(),
        lastKey: String = "",
    ) = LiveCatalog.assemble(useXtream, api, note, raw, preloadDone, favorites, lastKey)

    private fun failed(o: LiveCatalog.Opening) = (o as LiveCatalog.Opening.Failed).reason
    private fun ready(o: LiveCatalog.Opening) = o as LiveCatalog.Opening.Ready

    // ---------- de onde vem a sessão ----------

    @Test
    fun `catalogo da api pronto abre no ultimo canal assistido`() {
        val a = api(ch("A", "TV | X", "live", apiId = 1), ch("B", "TV | Y", "live", apiId = 2), order = listOf("TV | X", "TV | Y"))
        val r = ready(assemble(api = a, lastKey = "api:2"))
        assertEquals(1, r.startIndex)
        assertEquals("B", r.first.name)
        assertEquals(listOf("Todos", "X", "Y"), r.groups.map { it.name })
    }

    @Test
    fun `canal gravado que nao existe mais abre no primeiro`() {
        val a = api(ch("A", "X", "live", apiId = 1), order = listOf("X"))
        assertEquals(0, ready(assemble(api = a, lastKey = "api:999")).startIndex)
        assertEquals(0, ready(assemble(api = a, lastKey = "")).startIndex)
    }

    @Test
    fun `favoritos entram nos grupos da sessao`() {
        val a = api(ch("A", "X", "live", url = "http://x/a"), ch("B", "Y", "live", url = "http://x/b"), order = listOf("X", "Y"))
        val r = ready(assemble(api = a, favorites = setOf("http://x/b")))
        assertEquals(listOf("Todos", "Favoritos", "X", "Y"), r.groups.map { it.name })
    }

    @Test
    fun `sem api usa so os canais ao vivo da lista m3u em memoria`() {
        val raw = listOf(ch("A", "Esportes"), ch("Filme", "Filmes | X", url = "http://x/movie/1.mp4"), ch("B", "Notícias"))
        val r = ready(assemble(api = null, note = "TV ✗ SocketTimeoutException (usando M3U)", raw = raw))
        assertEquals(listOf("A", "B"), r.section.channels.map { it.name })
    }

    @Test
    fun `api desligada com lista m3u em memoria tambem abre`() {
        val raw = listOf(ch("A", "Esportes"), ch("B", "Notícias"))
        assertEquals(2, ready(assemble(useXtream = false, raw = raw)).section.channels.size)
    }

    // ---------- falhas com motivo ----------

    @Test
    fun `sem api e sem lista o motivo traz o que a api disse`() {
        val why = failed(assemble(api = null, note = "TV ✗ SocketTimeoutException (usando M3U)", raw = null))
        assertTrue(why, "catálogo da API indisponível" in why)
        assertTrue(why, "SocketTimeoutException" in why)
        assertFalse(why, "carregando" in why)
    }

    @Test
    fun `sem api e sem lista antes de o app terminar de carregar o motivo diz para tentar de novo`() {
        val why = failed(assemble(api = null, raw = null, preloadDone = false))
        assertTrue(why, "ainda está carregando" in why)
    }

    @Test
    fun `api desligada e sem lista tem motivo proprio`() {
        val why = failed(assemble(useXtream = false, api = null, raw = null))
        assertTrue(why, "API desligada" in why)
    }

    @Test
    fun `api sem canais ao vivo e lista m3u sem canais ao vivo`() {
        assertEquals("a API não trouxe canais ao vivo", failed(assemble(api = api())))
        val onlyVod = listOf(ch("Filme", "Filmes | X", url = "http://x/movie/1.mp4"))
        assertEquals("a lista M3U não tem canais ao vivo", failed(assemble(api = null, raw = onlyVod)))
        assertEquals("a lista M3U não tem canais ao vivo", failed(assemble(api = null, raw = emptyList())))
    }

    // ---------- o motivo nunca vaza endereço ----------

    @Test
    fun `motivo com endereco completo e escondido`() {
        val leaked = "Falha em http://painel.exemplo.com:8080/get.php?username=fulano&password=segredo e mais HTTPS://outro.exemplo/x"
        val why = LiveCatalog.Opening.Failed(leaked).reason
        assertFalse(why, "painel.exemplo" in why)
        assertFalse(why, "fulano" in why)
        assertFalse(why, "segredo" in why)
        assertFalse(why, "outro.exemplo" in why)
        assertEquals("Falha em [endereço] e mais [endereço]", why)
    }

    @Test
    fun `nota da api com endereco tambem passa pelo filtro`() {
        val why = failed(assemble(api = null, note = "falhou em http://h:80/player_api.php?username=u&password=p", raw = null))
        assertFalse(why, "player_api" in why)
        assertFalse(why, "password" in why)
    }

    @Test
    fun `texto sem endereco passa intacto`() {
        assertEquals("TV ✗ HTTP 403 (usando M3U)", LiveCatalog.scrub("TV ✗ HTTP 403 (usando M3U)"))
    }

    // ---------- esperar o carregamento da abertura ----------

    private class Clock(var t: Long = 0L)

    @Test
    fun `espera ate a condicao ficar verdadeira`() {
        val clock = Clock()
        var calls = 0
        val ok = LiveCatalog.waitUntil(10_000, 100, done = { calls++ >= 3 }, now = { clock.t }, sleep = { clock.t += it })
        assertTrue(ok)
        assertEquals(300L, clock.t)
    }

    @Test
    fun `condicao ja verdadeira nao espera`() {
        val clock = Clock()
        assertTrue(LiveCatalog.waitUntil(10_000, 100, done = { true }, now = { clock.t }, sleep = { clock.t += it }))
        assertEquals(0L, clock.t)
    }

    @Test
    fun `desiste no prazo e devolve falso`() {
        val clock = Clock()
        val ok = LiveCatalog.waitUntil(1_000, 100, done = { false }, now = { clock.t }, sleep = { clock.t += it })
        assertFalse(ok)
        assertEquals(1_000L, clock.t)
    }
}
