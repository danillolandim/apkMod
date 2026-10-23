package com.seupainel.player.playlist

/**
 * Reconhece se um título é LEGENDADO (marcado com "(LEG)", "LEGENDADO"…) ou
 * DUBLADO (sem marca, ou "(DUB)"), para juntar as duas versões do mesmo
 * filme/série num item só e deixar a escolha no detalhe.
 */
object Dub {

    private val LEG = Regex("""(?i)(\(\s*(leg|legendado|legendada)\s*\)|\[\s*(leg|legendado|legendada)\s*\]|[-–]\s*(legendado|legendada)\s*$|\s(leg|legendado|legendada)\s*$)""")
    private val DUB = Regex("""(?i)(\(\s*(dub|dublado|dublada)\s*\)|\[\s*(dub|dublado|dublada)\s*\]|[-–]\s*(dublado|dublada)\s*$|\s(dub|dublado|dublada)\s*$)""")

    private val reTrailSep = Regex("""[\s\-–|•:]+$""")
    private val reLeadSep = Regex("""^[\s\-–|•:]+""")
    private val reSpaces = Regex("""\s{2,}""")
    private val baseCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** true se o título é legendado. Sem marca = dublado (padrão). */
    fun isLeg(name: String): Boolean = LEG.containsMatchIn(name)

    /** Nome sem a marca de dublado/legendado, para agrupar as duas versões. */
    fun base(name: String): String = baseCache.getOrPut(name) {
        var s = name.replace(LEG, " ").replace(DUB, " ")
        s = s.replace(reTrailSep, "").replace(reLeadSep, "")
        s = s.replace(reSpaces, " ").trim()
        s.ifBlank { name.trim() }
    }
}
