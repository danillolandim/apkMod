package com.seupainel.player.playlist

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Catálogo de filmes vindo da API Xtream, com cache em disco.
 *
 * Primeira abertura: baixa e espera (o app mostra o "carregando"). Depois: abre do cache
 * na hora e atualiza em segundo plano quando passa do intervalo de atualização.
 * Qualquer falha devolve null e o app segue com a lista M3U, como antes.
 */
object XtreamCatalog {

    class Movies(val channels: List<M3uParser.Channel>, val categoryOrder: List<String>)

    private const val OTHERS = "Filmes | Outros"
    private val ADULT_MARKERS = listOf("adulto", "adult", "xxx")

    @Volatile private var memo: Movies? = null
    @Volatile private var memoStamp = ""
    private val refreshing = AtomicBoolean(false)

    @Synchronized
    fun movies(ctx: Context, store: PlaylistStore): Movies? {
        val acc = XtreamApi.accountFrom(store.lastUrl ?: store.playlistUrl) ?: return null
        val vodFile = File(ctx.filesDir, "xtream_vod.json")
        val catFile = File(ctx.filesDir, "xtream_vod_cats.json")
        val ua = store.userAgent

        if (!vodFile.exists() || vodFile.length() < 3) {
            return runCatching { fetchAndStore(acc, ua, vodFile, catFile) }.getOrNull()
        }
        if (isStale(vodFile, store.refreshHours) && refreshing.compareAndSet(false, true)) {
            Thread({
                try { runCatching { fetchAndStore(acc, ua, vodFile, catFile) } }
                finally { refreshing.set(false) }
            }, "xtream-refresh").start()
        }
        val stamp = "${vodFile.lastModified()}:${vodFile.length()}:${acc.base}"
        memo?.let { if (memoStamp == stamp) return it }
        val built = runCatching { build(acc, vodFile, catFile) }.getOrNull() ?: return null
        memo = built; memoStamp = stamp
        return built
    }

    /** Baixa para arquivos temporários e só troca o cache se o catálogo novo for válido. */
    private fun fetchAndStore(acc: XtreamApi.Account, ua: String?, vodFile: File, catFile: File): Movies? {
        val tmpVod = File(vodFile.path + ".tmp")
        val tmpCat = File(catFile.path + ".tmp")
        try {
            XtreamApi.downloadTo(XtreamApi.apiUrl(acc, "get_vod_categories"), ua, tmpCat)
            XtreamApi.downloadTo(XtreamApi.apiUrl(acc, "get_vod_streams"), ua, tmpVod)
            val built = build(acc, tmpVod, tmpCat)
            if (built == null || built.channels.isEmpty()) return null
            tmpCat.renameTo(catFile)
            if (!tmpVod.renameTo(vodFile)) return null
            synchronized(this) {
                memo = built
                memoStamp = "${vodFile.lastModified()}:${vodFile.length()}:${acc.base}"
            }
            return built
        } finally {
            tmpVod.delete(); tmpCat.delete()
        }
    }

    private fun isStale(f: File, refreshHours: Int): Boolean =
        refreshHours > 0 && System.currentTimeMillis() - f.lastModified() > refreshHours * 3_600_000L

    private fun build(acc: XtreamApi.Account, vodFile: File, catFile: File): Movies? {
        val cats = if (catFile.exists()) XtreamApi.parseCategories(catFile) else emptyList()
        val vods = XtreamApi.parseVod(vodFile)
        if (vods.isEmpty()) return null

        val nameById = cats.associate { it.id to it.name }
        val hidden = cats.filter { c -> ADULT_MARKERS.any { c.name.contains(it, ignoreCase = true) } }
            .map { it.id }.toHashSet()

        val used = HashSet<String>()
        val out = ArrayList<M3uParser.Channel>(vods.size)
        for (v in vods) {
            if (v.isAdult || v.categoryId in hidden) continue
            val group = nameById[v.categoryId] ?: OTHERS
            used.add(group)
            out.add(
                M3uParser.Channel(
                    name = v.name,
                    url = XtreamApi.movieUrl(acc, v),
                    group = group,
                    logo = v.icon,
                    duration = 0,
                    kind = KIND_MOVIE,
                    added = v.added,
                    rating = v.rating,
                    tmdbId = v.tmdbId,
                )
            )
        }
        // Mesma ordem em que o painel lista as categorias.
        val order = cats.map { it.name }.filter { it in used && ADULT_MARKERS.none { m -> it.contains(m, true) } }
            .distinct().toMutableList()
        if (OTHERS in used) order.add(OTHERS)
        return Movies(out, order)
    }

    const val KIND_MOVIE = "movie"
}
