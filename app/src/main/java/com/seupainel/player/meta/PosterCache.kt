package com.seupainel.player.meta

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache global de pôsteres do TMDB, compartilhado entre a lista (grid) e a tela
 * de detalhes — assim o cartaz é o MESMO nos dois lugares. Chave por título
 * limpo (ignora SxxExx/qualidade/ano), então episódios e variações batem.
 */
object PosterCache {
    private val map = ConcurrentHashMap<String, String>()

    private fun key(name: String) =
        Tmdb.cleanTitle(name).lowercase().ifBlank { name.lowercase() }

    fun get(name: String): String? = map[key(name)]

    fun put(name: String, posterUrl: String?) {
        if (!posterUrl.isNullOrBlank()) map[key(name)] = posterUrl
    }
}
