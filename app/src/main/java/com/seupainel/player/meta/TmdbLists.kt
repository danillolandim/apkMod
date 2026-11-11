package com.seupainel.player.meta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Nota, votos, gênero e ano dos títulos mais bem avaliados e mais populares do TMDB.
 *
 * O painel entrega o `tmdb_id` exato de cada título, então basta baixar as listas
 * `top_rated` e `popular` (20 títulos por página, até 500 páginas cada) e casar por id:
 * algumas centenas de requisições em vez de uma busca por título. Fica em disco por 7 dias.
 */
object TmdbLists {

    private const val MAX_PAGES = 500
    private const val THREADS = 6
    private const val RETRY_WAIT_MS = 1_200L
    private const val TTL_MS = 7L * 24 * 3_600_000

    // false = filmes, true = séries (os ids dos dois espaços são independentes).
    private val maps = ConcurrentHashMap<Boolean, Map<Int, VodIndex.Entry>>()

    fun entry(tv: Boolean, id: Int): VodIndex.Entry? = maps[tv]?.get(id)

    /** Lê o cache em disco para a memória. Devolve true se há algo para usar. */
    fun loadCached(ctx: Context): Boolean {
        var any = false
        for (tv in listOf(false, true)) {
            val loaded = maps[tv]
            if (loaded != null) { any = any || loaded.isNotEmpty(); continue }
            val f = file(ctx, tv)
            if (!f.exists()) continue
            runCatching { read(f) }.getOrNull()?.let { maps[tv] = it; any = any || it.isNotEmpty() }
        }
        return any
    }

    /** Baixa o que estiver velho ou faltando. Bloqueia. Devolve true se algo novo entrou. */
    @Synchronized
    fun ensure(ctx: Context, apiKey: String): Boolean {
        var changed = false
        for (tv in listOf(false, true)) {
            val f = file(ctx, tv)
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS && maps[tv] != null) continue
            val fetched = harvest(apiKey, tv)
            if (fetched.size < MIN_PLAUSIBLE) continue      // falha de rede/chave: mantém o cache antigo
            runCatching { write(f, fetched) }
            maps[tv] = fetched
            changed = true
        }
        return changed
    }

    private const val MIN_PLAUSIBLE = 500

    private fun harvest(apiKey: String, tv: Boolean): Map<Int, VodIndex.Entry> {
        val out = ConcurrentHashMap<Int, VodIndex.Entry>()
        val kind = if (tv) "tv" else "movie"
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            for (list in listOf("top_rated", "popular")) {
                val total = page(apiKey, kind, list, 1, tv, out) ?: continue
                val last = minOf(total, MAX_PAGES)
                (2..last).map { p -> pool.submit { page(apiKey, kind, list, p, tv, out) } }
                    .forEach { runCatching { it.get() } }
            }
        } finally {
            pool.shutdown()
        }
        return out
    }

    /** Lê uma página e devolve o total de páginas (null se falhou). */
    private fun page(
        apiKey: String, kind: String, list: String, p: Int, tv: Boolean,
        out: ConcurrentHashMap<Int, VodIndex.Entry>,
    ): Int? {
        repeat(3) {
            val (code, json) = Tmdb.req(apiKey, "/$kind/$list", mapOf("page" to p.toString()))
            if (code == 429) { Thread.sleep(RETRY_WAIT_MS); return@repeat }
            if (code !in 200..299 || json == null) return null
            val results = json.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val o = results.optJSONObject(i) ?: continue
                    val id = o.optInt("id")
                    if (id <= 0) continue
                    val g = o.optJSONArray("genre_ids")
                    val ids = if (g != null) (0 until g.length()).map { g.optInt(it) } else emptyList()
                    val date = o.optString(if (tv) "first_air_date" else "release_date", "")
                    // top_rated é lida antes de popular: putIfAbsent mantém a 1ª ocorrência.
                    out.putIfAbsent(id, VodIndex.Entry(
                        ids, date.take(4).toIntOrNull(),
                        o.optDouble("vote_average", 0.0), o.optInt("vote_count", 0), tv,
                    ))
                }
            }
            return json.optInt("total_pages", 0).takeIf { it > 0 }
        }
        return null
    }

    private fun file(ctx: Context, tv: Boolean) =
        File(ctx.filesDir, if (tv) "tmdb_lists_tv.json" else "tmdb_lists_movie.json")

    private fun write(f: File, m: Map<Int, VodIndex.Entry>) {
        val root = JSONObject()
        m.forEach { (id, e) ->
            root.put(id.toString(), JSONArray()
                .put(e.rating).put(e.votes).put(e.year ?: 0).put(e.genreIds.joinToString(",")))
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }

    private fun read(f: File): Map<Int, VodIndex.Entry> {
        val tv = f.name.contains("_tv")
        val root = JSONObject(f.readText())
        val out = HashMap<Int, VodIndex.Entry>(root.length() * 2)
        for (k in root.keys()) {
            val id = k.toIntOrNull() ?: continue
            val a = root.optJSONArray(k) ?: continue
            val ids = a.optString(3).split(',').mapNotNull { it.toIntOrNull() }
            out[id] = VodIndex.Entry(ids, a.optInt(2).takeIf { it > 0 }, a.optDouble(0), a.optInt(1), tv)
        }
        return out
    }
}
