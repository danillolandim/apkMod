package com.seupainel.player.search

import org.json.JSONArray

/** Lista das últimas buscas (a mais nova primeiro). Funções puras: quem guarda em disco é o [SearchHistory]. */
object RecentSearches {

    const val MAX = 8
    private const val MIN_CHARS = 2

    fun add(list: List<String>, term: String): List<String> {
        val clean = term.trim().replace(Regex("""\s+"""), " ")
        if (clean.length < MIN_CHARS) return list
        val key = SearchText.normalize(clean)
        return (listOf(clean) + list.filter { SearchText.normalize(it) != key }).take(MAX)
    }

    fun remove(list: List<String>, term: String): List<String> {
        val key = SearchText.normalize(term)
        return list.filter { SearchText.normalize(it) != key }
    }

    fun encode(list: List<String>): String = JSONArray(list).toString()

    fun decode(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
