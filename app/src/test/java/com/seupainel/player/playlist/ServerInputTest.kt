package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerInputTest {

    @Test
    fun `dns solto ganha http`() {
        assertEquals("http://servidor.com:8080", ServerInput.parse("servidor.com:8080")?.base)
        assertEquals("http://servidor.com", ServerInput.parse("servidor.com")?.base)
    }

    @Test
    fun `mantem https e ignora sufixos e barra final`() {
        assertEquals("https://x.com", ServerInput.parse("https://x.com/")?.base)
        assertEquals("http://x.com:80", ServerInput.parse("http://x.com:80/c/")?.base)
        assertEquals("http://x.com", ServerInput.parse("http://x.com/get.php")?.base)
    }

    @Test
    fun `espacos no meio sao ignorados`() {
        assertEquals("http://servidor.com:8080", ServerInput.parse("  http://servidor.com :8080 ")?.base)
    }

    @Test
    fun `url completa colada separa servidor usuario e senha`() {
        val p = ServerInput.parse("http://srv.com:8080/get.php?username=fulano&password=segredo&type=m3u_plus&output=m3u8")
        assertEquals("http://srv.com:8080", p?.base)
        assertEquals("fulano", p?.user)
        assertEquals("segredo", p?.pass)
    }

    @Test
    fun `servidor solto nao traz usuario nem senha`() {
        val p = ServerInput.parse("srv.com")
        assertNull(p?.user)
        assertNull(p?.pass)
    }

    @Test
    fun `entrada invalida devolve null`() {
        assertNull(ServerInput.parse(null))
        assertNull(ServerInput.parse("   "))
        assertNull(ServerInput.parse("ftp://srv.com"))
    }
}
