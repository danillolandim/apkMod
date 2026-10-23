package com.seupainel.player.playlist

/**
 * Reconhece o sufixo de qualidade no nome do canal (HD, FHD, SD, 4K…) para
 * juntar "Globo HD", "Globo FHD", "Globo SD" num único item, deixando a troca
 * de qualidade como opção no player.
 */
object ChannelQuality {

    private val QUAL = Regex("""(?i)\b(4k|uhd|full\s*hd|fhd|hd\s*2|hd²|hevc|h\.?265|hd|sd)\b""")

    /** Rótulo normalizado da qualidade, ou "Padrão" quando o nome não traz sufixo. */
    fun labelOf(name: String): String {
        val m = QUAL.findAll(name).lastOrNull() ?: return "Padrão"
        return normalize(m.value)
    }

    private fun normalize(raw: String): String {
        val u = raw.uppercase().replace(" ", "").replace(".", "")
        return when {
            u == "4K" || u == "UHD" -> "4K"
            u == "FULLHD" || u == "FHD" -> "FHD"
            u == "HD2" || u == "HD²" -> "HD²"
            u == "HEVC" || u == "H265" -> "H265"
            u == "HD" -> "HD"
            u == "SD" -> "SD"
            else -> u
        }
    }

    /** Quanto maior, melhor a qualidade (para escolher o representante). */
    fun rank(name: String): Int = when (labelOf(name)) {
        "4K" -> 6
        "FHD" -> 5
        "HD²" -> 4
        "HD", "H265" -> 3
        "Padrão" -> 2
        "SD" -> 1
        else -> 2
    }

    private val reTrail = Regex("""[\s\-|•:]+$""")
    private val reLead = Regex("""^[\s\-|•:]+""")
    private val reSpaces = Regex("""\s{2,}""")
    private val baseCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Nome sem o(s) sufixo(s) de qualidade, para agrupar e exibir. */
    fun baseName(name: String): String = baseCache.getOrPut(name) {
        var s = name.replace(QUAL, " ")
        s = s.replace(reTrail, "")        // separadores soltos no fim
        s = s.replace(reLead, "")
        s = s.replace(reSpaces, " ").trim()
        s.ifBlank { name.trim() }
    }
}
