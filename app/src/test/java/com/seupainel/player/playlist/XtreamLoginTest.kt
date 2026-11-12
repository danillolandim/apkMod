package com.seupainel.player.playlist

import com.seupainel.player.playlist.XtreamApi.LoginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamLoginTest {

    private val now = 1_800_000_000_000L   // um instante fixo

    private fun msg(r: LoginResult) = (r as LoginResult.Fail).message

    @Test
    fun `conta ativa devolve validade e conexoes`() {
        val r = XtreamApi.parseLogin(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"4102444800","active_cons":"0","max_connections":"2"}}""", now)
        r as LoginResult.Ok
        assertEquals("Active", r.status)
        assertNotNull(r.expires)
        assertEquals(0, r.active)
        assertEquals(2, r.max)
    }

    @Test
    fun `sem data de validade a conta vale`() {
        val r = XtreamApi.parseLogin("""{"user_info":{"auth":1,"status":"Active","exp_date":null}}""", now)
        assertNull((r as LoginResult.Ok).expires)
    }

    @Test
    fun `senha errada`() {
        assertTrue(msg(XtreamApi.parseLogin("""{"user_info":{"auth":0}}""", now)).contains("incorretos"))
    }

    @Test
    fun `conta vencida`() {
        val r = XtreamApi.parseLogin("""{"user_info":{"auth":1,"status":"Active","exp_date":"1000000000"}}""", now)
        assertTrue(msg(r).contains("vencida"))
    }

    @Test
    fun `conta desativada ou bloqueada`() {
        assertTrue(msg(XtreamApi.parseLogin("""{"user_info":{"auth":1,"status":"Disabled"}}""", now)).contains("desativada"))
        assertTrue(msg(XtreamApi.parseLogin("""{"user_info":{"auth":1,"status":"Banned"}}""", now)).contains("bloqueada"))
    }

    @Test
    fun `resposta que nao e json ou nao traz conta`() {
        assertTrue(msg(XtreamApi.parseLogin("<html>erro</html>", now)).contains("Xtream"))
        assertTrue(msg(XtreamApi.parseLogin("{}", now)).contains("não informou"))
    }
}
