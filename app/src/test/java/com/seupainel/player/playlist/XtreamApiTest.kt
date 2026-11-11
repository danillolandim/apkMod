package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamApiTest {

    @Test
    fun `extrai conta de uma url get_php com porta`() {
        val acc = XtreamApi.accountFrom(
            "http://painel.exemplo.com:8080/get.php?username=fulano&password=segredo1&type=m3u_plus&output=m3u8"
        )
        assertEquals(XtreamApi.Account("http://painel.exemplo.com:8080", "fulano", "segredo1"), acc)
    }

    @Test
    fun `extrai conta sem porta e com https`() {
        val acc = XtreamApi.accountFrom("https://painel.exemplo.com/get.php?password=p&username=u")
        assertEquals(XtreamApi.Account("https://painel.exemplo.com", "u", "p"), acc)
    }

    @Test
    fun `url que nao e xtream devolve null`() {
        assertNull(XtreamApi.accountFrom(null))
        assertNull(XtreamApi.accountFrom(""))
        assertNull(XtreamApi.accountFrom("http://exemplo.com/lista.m3u"))
        assertNull(XtreamApi.accountFrom("http://exemplo.com/get.php?username=u"))
        assertNull(XtreamApi.accountFrom("ftp://exemplo.com/get.php?username=u&password=p"))
        assertNull(XtreamApi.accountFrom("isto nao e url"))
    }

    @Test
    fun `aceita host com underscore e caracteres que o URI rejeitaria`() {
        // java.net.URI devolve host nulo com "_" e lança exceção com "|" ou espaço.
        assertEquals(
            XtreamApi.Account("http://meu_painel.exemplo.com:25461", "u", "a|b c"),
            XtreamApi.accountFrom("http://meu_painel.exemplo.com:25461/get.php?username=u&password=a|b c&type=m3u_plus&output=m3u8"),
        )
    }

    @Test
    fun `chaves da query nao diferenciam maiusculas e senha com percent invalido nao quebra`() {
        val acc = XtreamApi.accountFrom("http://h/get.php?UserName=u&PassWord=100%zz")
        assertEquals(XtreamApi.Account("http://h", "u", "100%zz"), acc)
    }

    @Test
    fun `extrai conta de links de stream movie series e live`() {
        val esperado = XtreamApi.Account("http://h:8080", "usr", "pwd")
        assertEquals(esperado, XtreamApi.accountFromStreamUrl("http://h:8080/movie/usr/pwd/123.mp4"))
        assertEquals(esperado, XtreamApi.accountFromStreamUrl("http://h:8080/series/usr/pwd/9.mkv"))
        assertEquals(esperado, XtreamApi.accountFromStreamUrl("http://h:8080/live/usr/pwd/5.ts"))
    }

    @Test
    fun `link de stream sem credenciais devolve null`() {
        assertNull(XtreamApi.accountFromStreamUrl("http://h/movie/123.mp4"))
        assertNull(XtreamApi.accountFromStreamUrl("http://h/outra/usr/pwd/123.mp4"))
        assertNull(XtreamApi.accountFromStreamUrl(null))
    }

    @Test
    fun `shape mostra o formato sem host nem valores`() {
        val s = XtreamApi.shape("http://painel.exemplo.com/get.php?username=fulano&password=segredo&type=m3u_plus")
        assertEquals("http:/…/get.php params: username,password,type", s)
        assertEquals("sem URL", XtreamApi.shape(null))
        // caminho que parece token não é exibido
        assertEquals("https:/…/(outro) params: nenhum", XtreamApi.shape("https://x.com/AbC123tokenSecreto"))
    }

    @Test
    fun `monta url da api codificando usuario e senha`() {
        val acc = XtreamApi.Account("http://h:80", "us er", "p&ss")
        assertEquals(
            "http://h:80/player_api.php?username=us+er&password=p%26ss&action=get_vod_streams",
            XtreamApi.apiUrl(acc, "get_vod_streams"),
        )
    }

    @Test
    fun `monta url de reproducao do filme no formato do get_php`() {
        val acc = XtreamApi.Account("http://h:80", "u", "p")
        val v = XtreamApi.Vod(1259895, "Filme", 1, null, 5.0, 1751137685, "137", "mkv")
        assertEquals("http://h:80/movie/u/p/1259895.mkv", XtreamApi.movieUrl(acc, v))
    }
}
