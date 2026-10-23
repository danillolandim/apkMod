package com.seupainel.player.playlist

/**
 * Reconhece se um título é LEGENDADO (marcado com "(LEG)", "LEGENDADO"…) ou
 * DUBLADO (sem marca, ou "(DUB)"), para juntar as duas versões do mesmo
 * filme/série num item só e deixar a escolha no detalhe.
 */
object Dub {

    private val LEG = Regex("""(?i)(\(\s*(leg|legendado|legendada)\s*\)|\[\s*(leg|legendado|legendada)\s*\]|[-–]\s*(legendado|legendada)\s*$|\s(leg|legendado|legendada)\s*$)""")
    private val DUB = Regex("""(?i)(\(\s*(dub|dublado|dublada)\s*\)|\[\s*(dub|dublado|dublada)\s*\]|[-–]\s*(dublado|dublada)\s*$|\s(dub|dublado|dublada)\s*$)""")

    /** true se o título é legendado. Sem marca = dublado (padrão). */
    fun isLeg(name: String): Boolean = LEG.containsMatchIn(name)

    /** Nome sem a marca de dublado/legendado, para agrupar as duas versões. */
    fun base(name: String): String {
        var s = name.replace(LEG, " ").replace(DUB, " ")
        s = s.replace(Regex("""[\s\-–|•:]+$"""), "").replace(Regex("""^[\s\-–|•:]+"""), "")
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return s.ifBlank { name.trim() }
    }
}
