package com.seupainel.player.meta

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Índice persistente de metadados de VOD (gênero + ano) obtidos do TMDB.
 *
 * A lista M3U do usuário não traz gênero nem ano — só "FILMES (BR)". Então o
 * app busca essa informação no TMDB (uma vez por título) e guarda aqui, em
 * memória e em disco (JSON no filesDir), para as subcategorias e filtros.
 *
 * Chave: título limpo (Tmdb.cleanTitle) em minúsculas.
 */
class VodIndex(context: Context) {

    data class Entry(val genreIds: List<Int>, val year: Int?)

    private val file = File(context.filesDir, "vod_index.json")
    private val map = ConcurrentHashMap<String, Entry>()
    // Contagem de cada gênero, mantida incrementalmente (evita varrer o índice
    // inteiro — que tem dezenas de milhares de itens — a cada atualização).
    private val tally = ConcurrentHashMap<Int, Int>()
    @Volatile private var dirty = false

    init { load() }

    private fun keyOf(title: String) =
        Tmdb.cleanTitle(title).lowercase().ifBlank { title.lowercase() }

    fun has(title: String) = map.containsKey(keyOf(title))
    fun get(title: String): Entry? = map[keyOf(title)]
    fun year(title: String): Int? = map[keyOf(title)]?.year
    fun genres(title: String): List<Int> = map[keyOf(title)]?.genreIds ?: emptyList()
    fun size() = map.size

    fun put(title: String, genreIds: List<Int>, year: Int?) {
        val k = keyOf(title)
        if (map.put(k, Entry(genreIds, year)) == null) {        // só conta na 1ª vez
            genreIds.forEach { id -> tally.merge(id, 1) { a, b -> a + b } }
        }
        dirty = true
    }

    /** Contagem de cada gênero presente no índice (barato: ~27 entradas). */
    fun genreCounts(): Map<Int, Int> = HashMap(tally)

    @Synchronized
    fun saveIfDirty() {
        if (!dirty) return
        runCatching {
            val root = JSONObject()
            map.forEach { (k, v) ->
                val o = JSONObject()
                o.put("g", v.genreIds.joinToString(","))
                v.year?.let { o.put("y", it) }
                root.put(k, o)
            }
            file.writeText(root.toString())
            dirty = false
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            for (k in root.keys()) {
                val o = root.getJSONObject(k)
                val ids = o.optString("g").split(",").mapNotNull { it.toIntOrNull() }
                val y = if (o.has("y")) o.optInt("y") else null
                map[k] = Entry(ids, y)
                ids.forEach { id -> tally.merge(id, 1) { a, b -> a + b } }
            }
        }
    }

    companion object {
        /** id de gênero do TMDB -> nome em PT (filmes + TV). */
        val GENRES: Map<Int, String> = linkedMapOf(
            28 to "Ação",
            12 to "Aventura",
            16 to "Animação",
            35 to "Comédia",
            80 to "Crime",
            99 to "Documentário",
            18 to "Drama",
            10751 to "Família",
            14 to "Fantasia",
            36 to "História",
            27 to "Terror",
            10402 to "Música",
            9648 to "Mistério",
            10749 to "Romance",
            878 to "Ficção científica",
            53 to "Suspense",
            10752 to "Guerra",
            37 to "Faroeste",
            10770 to "Filme de TV",
            10759 to "Ação e Aventura",
            10762 to "Infantil",
            10763 to "Notícias",
            10764 to "Reality",
            10765 to "Sci-Fi e Fantasia",
            10766 to "Novela",
            10767 to "Talk Show",
            10768 to "Guerra e Política",
        )

        /** nome do gênero -> id (reverso). */
        val GENRE_BY_NAME: Map<String, Int> = GENRES.entries.associate { (k, v) -> v to k }
    }
}
