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
