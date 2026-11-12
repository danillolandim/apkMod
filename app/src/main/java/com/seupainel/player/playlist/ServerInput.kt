package com.seupainel.player.playlist

import java.net.URL

/** Interpreta o que a pessoa digita no campo "Servidor": DNS solto, com porta, ou a URL completa do get.php. */
object ServerInput {

    data class Parsed(val base: String, val user: String?, val pass: String?)

    fun parse(raw: String?): Parsed? {
        val t = raw.orEmpty().filterNot { it.isWhitespace() }
        if (t.isEmpty()) return null
        val withScheme = if (t.contains("://")) t else "http://$t"
        // URL completa colada (get.php?username=…&password=…): separa servidor, usuário e senha.
        XtreamApi.accountFrom(withScheme)?.let { return Parsed(it.base, it.user, it.pass) }
        val url = runCatching { URL(withScheme) }.getOrNull() ?: return null
        val proto = url.protocol?.lowercase()
        if (proto != "http" && proto != "https") return null
        val host = url.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (url.port > 0) ":${url.port}" else ""
        // Só scheme + host + porta: sufixos como /c, /get.php ou barra final não importam.
        return Parsed("$proto://$host$port", null, null)
    }
}
