package com.seupainel.player.playlist

/**
 * Agrupa episódios soltos de uma lista M3U em séries → temporadas → episódios.
 *
 * Reconhece os padrões de nome mais comuns em listas IPTV:
 *   "Nome da Série S01E02", "Nome SxEx", "Nome 1x02", "Nome T01E02"
 * O que vem antes do marcador vira o nome da série.
 */
object SeriesGrouper {

    // S01E02 / S1E2 / T01E02 ; e o formato 1x02
    private val reSE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")
    private val reTE = Regex("""(?i)\bT(\d{1,2})\s*E(\d{1,3})\b""")
    private val reX = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")

    data class Ep(
        val season: Int,
        val episode: Int,
        val channel: M3uParser.Channel,
        val leg: Boolean = false,          // legendado? (senão, dublado)
    )

    data class Series(
        val title: String,
        val logo: String?,
        val episodes: List<Ep>,
    ) {
        val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
        fun episodesOf(season: Int) = episodes.filter { it.season == season }.sortedBy { it.episode }
    }

    /** Extrai (nomeBase, temporada, episódio) do nome, ou null se não casar. */
    fun parse(name: String): Triple<String, Int, Int>? {
        for (re in listOf(reSE, reTE, reX)) {
            val m = re.find(name) ?: continue
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            val base = name.substring(0, m.range.first)
                .replace(Regex("""[-–|•]+\s*$"""), "")
                .trim()
                .ifBlank { name.trim() }
            return Triple(base, s, e)
        }
        return null
    }

    /** Agrupa uma lista (categoria de séries) em séries ordenadas por nome. */
    fun group(channels: List<M3uParser.Channel>): List<Series> {
        val map = LinkedHashMap<String, MutableList<Ep>>()
        val logos = HashMap<String, String?>()
        for (ch in channels) {
            val p = parse(ch.name)
            val frag = p?.first ?: ch.name
            // Chave sem a marca de dub/leg -> as duas versões caem na MESMA série.
            // O flag leg vem do MESMO fragmento (consistente com a chave).
            val key = Dub.base(frag).lowercase()
            val season = p?.second ?: 1
            val episode = p?.third ?: 1
            map.getOrPut(key) { ArrayList() }.add(Ep(season, episode, ch, Dub.isLeg(frag)))
            if (logos[key].isNullOrBlank() && !ch.logo.isNullOrBlank()) logos[key] = ch.logo
        }
        return map.entries.map { (key, eps) ->
            // título "bonito": nome-base (sem dub/leg) do primeiro episódio parseado
            val raw = eps.firstNotNullOfOrNull { parse(it.channel.name)?.first }
                ?: eps.first().channel.name
            val title = Dub.base(raw)
            Series(title.trim(), logos[key], eps.sortedWith(compareBy({ it.season }, { it.episode })))
        }.sortedBy { it.title.lowercase() }
    }
}
