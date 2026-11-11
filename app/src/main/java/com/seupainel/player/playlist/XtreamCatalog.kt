package com.seupainel.player.playlist

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Catálogo do provedor (Filmes, Séries e TV ao vivo) via API Xtream, com cache em disco.
 *
 * Primeira abertura: baixa e espera (o app mostra o "carregando"). Depois: abre do cache
 * na hora e atualiza em segundo plano quando passa do intervalo de atualização.
 * Qualquer falha devolve null e a seção segue com a lista M3U, como antes.
 */
object XtreamCatalog {

    class Section(val channels: List<M3uParser.Channel>, val categoryOrder: List<String>)

    enum class Kind(val tag: String, val label: String, val listAction: String, val catAction: String, val file: String) {
        MOVIE("movie", "Filmes", "get_vod_streams", "get_vod_categories", "xtream_vod"),
        SERIES("series", "Séries", "get_series", "get_series_categories", "xtream_series"),
        LIVE("live", "TV", "get_live_streams", "get_live_categories", "xtream_live"),
    }

    const val KIND_MOVIE = "movie"
    const val KIND_SERIES = "series"
    const val KIND_LIVE = "live"

    private val ADULT_MARKERS = listOf("adulto", "adult", "xxx")

    private class Memo(val stamp: String, val section: Section)
    private val memos = ConcurrentHashMap<Kind, Memo>()
    private val refreshing = Kind.values().associateWith { AtomicBoolean(false) }
    private val locks = Kind.values().associateWith { Any() }

    // Situação por seção, para o app avisar na tela (nunca contém usuário/senha).
    private val parts = ConcurrentHashMap<String, String>()
    var lastStatus: String
        get() = parts.entries.sortedBy { it.key }.joinToString(" · ") { it.value }
        set(v) { parts.clear(); if (v.isNotBlank()) parts["*"] = v }

    /** Conta da API: URL da lista, ou as credenciais embutidas nos links dos itens. */
    fun accountFor(store: PlaylistStore, raw: List<M3uParser.Channel>): XtreamApi.Account? =
        XtreamApi.accountFrom(store.lastUrl)
            ?: XtreamApi.accountFrom(store.playlistUrl)
            ?: raw.firstNotNullOfOrNull { ch ->
                if (ch.url.contains("/movie/", true) || ch.url.contains("/series/", true))
                    XtreamApi.accountFromStreamUrl(ch.url) else null
            }

    /** Quantos itens a seção já tem em memória (0 se ainda não carregou). Não bloqueia. */
    fun cachedCount(kind: Kind): Int = memos[kind]?.section?.channels?.size ?: 0

    /** Há cache em disco desta seção (dá para abrir sem baixar nada)? */
    fun hasCache(ctx: Context, kind: Kind): Boolean = File(ctx.filesDir, kind.file + ".json").let { it.exists() && it.length() > 2 }

    fun load(kind: Kind, ctx: Context, store: PlaylistStore, raw: List<M3uParser.Channel>): Section? =
        synchronized(locks.getValue(kind)) {
            val acc = accountFor(store, raw)
            if (acc == null) {
                parts["*"] = "API: não achei usuário/senha (lista: ${XtreamApi.shape(store.lastUrl ?: store.playlistUrl)}); " +
                    "usando a lista M3U"
                return null
            }
            parts.remove("*")
            val listFile = File(ctx.filesDir, kind.file + ".json")
            val catFile = File(ctx.filesDir, kind.file + "_cats.json")
            val ua = store.userAgent

            if (!listFile.exists() || listFile.length() < 3) {
                val r = runCatching { fetchAndStore(kind, acc, ua, listFile, catFile, raw) }
                r.exceptionOrNull()?.let {
                    parts[kind.label] = "${kind.label} ✗ ${XtreamApi.describe(it)} (usando M3U)"
                    return null
                }
                val s = r.getOrNull()
                parts[kind.label] = if (s == null) "${kind.label} ✗ resposta inválida (usando M3U)"
                                    else "${kind.label} ✓ ${s.channels.size}"
                return s
            }
            if (isStale(listFile, store.refreshHours) && refreshing.getValue(kind).compareAndSet(false, true)) {
                Thread({
                    try { runCatching { fetchAndStore(kind, acc, ua, listFile, catFile, raw) } }
                    finally { refreshing.getValue(kind).set(false) }
                }, "xtream-refresh-${kind.tag}").start()
            }
            val stamp = "${listFile.lastModified()}:${listFile.length()}:${acc.base}"
            memos[kind]?.let {
                if (it.stamp == stamp) { parts[kind.label] = "${kind.label} ✓ ${it.section.channels.size}"; return it.section }
            }
            val built = runCatching { build(kind, acc, listFile, catFile, raw) }.getOrNull()
            if (built == null) {
                parts[kind.label] = "${kind.label} ✗ cache ilegível (usando M3U)"
                return null
            }
            memos[kind] = Memo(stamp, built)
            parts[kind.label] = "${kind.label} ✓ ${built.channels.size}"
            return built
        }

    /** Baixa para arquivos temporários e só troca o cache se o catálogo novo for válido. */
    private fun fetchAndStore(
        kind: Kind, acc: XtreamApi.Account, ua: String?, listFile: File, catFile: File,
        raw: List<M3uParser.Channel>,
    ): Section? {
        val tmpList = File(listFile.path + ".tmp")
        val tmpCat = File(catFile.path + ".tmp")
        try {
            XtreamApi.downloadTo(XtreamApi.apiUrl(acc, kind.catAction), ua, tmpCat)
            XtreamApi.downloadTo(XtreamApi.apiUrl(acc, kind.listAction), ua, tmpList)
            val built = build(kind, acc, tmpList, tmpCat, raw)
            if (built == null || built.channels.isEmpty()) return null
            tmpCat.renameTo(catFile)
            if (!tmpList.renameTo(listFile)) return null
            memos[kind] = Memo("${listFile.lastModified()}:${listFile.length()}:${acc.base}", built)
            return built
        } finally {
            tmpList.delete(); tmpCat.delete()
        }
    }

    private fun isStale(f: File, refreshHours: Int): Boolean =
        refreshHours > 0 && System.currentTimeMillis() - f.lastModified() > refreshHours * 3_600_000L

    private fun isAdult(name: String) = ADULT_MARKERS.any { name.contains(it, ignoreCase = true) }

    private fun build(
        kind: Kind, acc: XtreamApi.Account, listFile: File, catFile: File, raw: List<M3uParser.Channel>,
    ): Section? {
        val cats = if (catFile.exists()) XtreamApi.parseCategories(catFile) else emptyList()
        val nameById = cats.associate { it.id to it.name }
        val hidden = cats.filter { isAdult(it.name) }.map { it.id }.toHashSet()
        val others = "${kind.label} | Outros"
        val used = HashSet<String>()
        val out = ArrayList<M3uParser.Channel>()

        when (kind) {
            Kind.MOVIE -> for (v in XtreamApi.parseVod(listFile)) {
                if (v.isAdult || v.categoryId in hidden) continue
                val group = nameById[v.categoryId] ?: others
                used.add(group)
                out.add(M3uParser.Channel(
                    name = v.name, url = XtreamApi.movieUrl(acc, v), group = group, logo = v.icon, duration = 0,
                    kind = KIND_MOVIE, added = v.added, rating = v.rating, tmdbId = v.tmdbId, apiId = v.id,
                ))
            }
            Kind.SERIES -> for (s in XtreamApi.parseSeries(listFile)) {
                if (s.categoryId in hidden) continue
                val group = nameById[s.categoryId] ?: others
                used.add(group)
                // A "url" é só uma chave interna: os episódios (com URLs reais) vêm ao abrir a série.
                out.add(M3uParser.Channel(
                    name = s.name, url = "xtream:series:${s.id}", group = group, logo = s.cover, duration = 0,
                    kind = KIND_SERIES, added = s.modified, rating = s.rating, tmdbId = s.tmdbId, apiId = s.id,
                ))
            }
            Kind.LIVE -> {
                val template = liveTemplate(acc, raw)
                for (l in XtreamApi.parseLive(listFile)) {
                    if (l.isAdult || l.categoryId in hidden) continue
                    val group = nameById[l.categoryId] ?: others
                    used.add(group)
                    out.add(M3uParser.Channel(
                        name = l.name, url = template.replace("{id}", l.id.toString()), group = group,
                        logo = l.icon, tvgId = l.epgId, duration = -1,
                        kind = KIND_LIVE, added = l.added, apiId = l.id,
                    ))
                }
            }
        }
        if (out.isEmpty()) return null

        // Mesma ordem em que o painel lista as categorias.
        val order = cats.map { it.name }.filter { it in used && !isAdult(it) }.distinct().toMutableList()
        if (others in used) order.add(others)
        return Section(out, order)
    }

    /**
     * Formato da URL de canal ao vivo, copiado de um canal da própria lista M3U (com ou sem
     * "/live/", com ".ts", ".m3u8" ou sem extensão). Assim a URL fica idêntica à de antes e os
     * Favoritos (guardados pela URL) continuam valendo.
     */
    internal fun liveTemplate(acc: XtreamApi.Account, raw: List<M3uParser.Channel>): String {
        val marker = "/${acc.user}/${acc.pass}/"
        for (ch in raw) {
            if (!ch.isLive) continue
            val i = ch.url.indexOf(marker)
            if (i < 0) continue
            val tail = ch.url.substring(i + marker.length)
            val ext = tail.substringAfter('.', "").substringBefore('?')
            return ch.url.substring(0, i) + marker + "{id}" + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return "${acc.base}/live/${acc.user}/${acc.pass}/{id}.ts"
    }
}
