package com.seupainel.player.playlist

/**
 * Deduz a categoria de um canal AO VIVO a partir do nome, quando a lista não
 * traz subcategorias reais (todos no grupo "CANAIS"). Baseado em palavras-chave
 * comuns de listas IPTV BR. A ordem das regras é a prioridade (a primeira que
 * casar vence), então categorias específicas vêm antes das genéricas.
 */
object LiveGroups {

    private val RULES: List<Pair<String, List<String>>> = listOf(
        "Esportes" to listOf(
            "ESPN", "SPORTV", "SPORT TV", "PREMIERE", "COMBATE", "BANDSPORTS", "BAND SPORTS",
            "TNT SPORTS", "FOX SPORTS", "NOSSO FUTEBOL", "FUTEBOL", "NBA", "UFC", "SPORTS", "ESPORTE",
        ),
        "Notícias" to listOf(
            "GLOBONEWS", "GLOBO NEWS", "CNN", "BAND NEWS", "BANDNEWS", "RECORD NEWS",
            "JOVEM PAN", "NEWS", "NOTÍCIA", "NOTICIA",
        ),
        "Infantil" to listOf(
            "CARTOON", "DISNEY", "GLOOB", "NICKELODEON", "NICK", "DISCOVERY KIDS", "ZOOMOO",
            "BOOMERANG", "BABYTV", "KIDS", "INFANTIL",
        ),
        "Documentários" to listOf(
            "DISCOVERY", "HISTORY", "HISTÓRIA", "HISTORIA", "NAT GEO", "NATGEO", "GEOGRAPHIC",
            "ANIMAL PLANET", "INVESTIGATION", "H2", "TRAVEL BOX", "SCIENCE", "CURIOSITY", "DOC",
        ),
        "Filmes & Séries" to listOf(
            "TELECINE", "HBO", " MAX", "MEGAPIX", "TNT", "SPACE", " FX", "UNIVERSAL", "AXN",
            "WARNER", "SONY", "PARAMOUNT", "CINEMAX", "TCM", "A&E", "STUDIO", "CINE", "MOVIE", "FILMES",
        ),
        "Variedades" to listOf(
            "GNT", "MULTISHOW", "COMEDY", "E!", "LIFETIME", "TLC", "FOOD", "BIS", "VIVA",
            "FISHTV", "CURTA", "MODA", "OFF",
        ),
        "Música" to listOf("MTV", "MUSIC", "MÚSICA", "MUSICA"),
        "Religioso" to listOf(
            "CANÇÃO NOVA", "CANCAO NOVA", "REDE VIDA", "APARECIDA", "GOSPEL", "IGREJA",
            "GÊNESIS", "GENESIS", "BOA VONTADE", "SÉCULO 21", "SECULO 21",
        ),
        "Abertos" to listOf(
            "GLOBO", "SBT", "RECORD", "BAND", "REDETV", "REDE TV", "CULTURA", "TV BRASIL",
        ),
    )

    /** Categoria do canal, ou "Outros" se nenhuma palavra-chave casar. */
    fun categoryOf(name: String): String {
        val u = name.uppercase()
        for ((cat, kws) in RULES) if (kws.any { u.contains(it) }) return cat
        return "Outros"
    }

    /** Ordem fixa para exibir as categorias (as presentes, nesta sequência). */
    val ORDER: List<String> = RULES.map { it.first } + "Outros"
}
