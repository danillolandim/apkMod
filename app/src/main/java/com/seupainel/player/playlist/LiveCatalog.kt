package com.seupainel.player.playlist

import android.content.Context

/**
 * A seção de TV ao vivo inteira, montada a partir da API da conta ou da lista M3U: canais juntados por qualidade
 * (a mesma regra do menu da tela de canais) e todas as categorias, na ordem do menu.
 *
 * A parte pura ([build], [groupsOf] e os auxiliares) não usa Android e tem testes JVM; [openSession] é a cola com
 * o catálogo em cache, usada pela Home para abrir o player direto.
 */
object LiveCatalog {

    class Section(val channels: List<LiveChannel>, val categories: List<LiveGroup>)

    enum class GroupKind { LIVE, MOVIE, SERIES }

    /** O que o nome do grupo (group-title) indica, para listas M3U sem o tipo vindo da API. */
    fun groupKind(group: String): GroupKind {
        val u = group.uppercase()
        return when {
            u.contains("SERIE") || u.contains("SÉRIE") -> GroupKind.SERIES
            u.contains("FILME") || u.contains("MOVIE") || u.contains("VOD") || u.contains("CINEMA") -> GroupKind.MOVIE
            else -> GroupKind.LIVE
        }
    }

    /** Item da seção ao vivo? A API já diz o tipo; sem ele vale o nome do grupo. */
    fun isLive(ch: M3uParser.Channel): Boolean = when (ch.kind) {
        XtreamCatalog.KIND_LIVE -> true
        XtreamCatalog.KIND_MOVIE, XtreamCatalog.KIND_SERIES -> false
        else -> groupKind(ch.group) == GroupKind.LIVE
    }

    private enum class Naming { API, REAL, DERIVED }

    /**
     * [live] são só os canais ao vivo, na ordem da lista. [apiOrder] é a ordem das categorias do painel (vazio
     * quando a seção vem do M3U): com ela as categorias são as da API; sem ela, as do group-title se houver mais
     * de uma e, senão, as deduzidas do nome do canal (Esportes, Notícias…).
     */
    fun build(live: List<M3uParser.Channel>, apiOrder: List<String> = emptyList()): Section {
        if (live.isEmpty()) return Section(emptyList(), emptyList())
        val naming = when {
            apiOrder.isNotEmpty() -> Naming.API
            live.map { it.group.trim() }.filter { it.isNotEmpty() }.toSet().size > 1 -> Naming.REAL
            else -> Naming.DERIVED
        }
        fun categoryOf(c: M3uParser.Channel): String = when (naming) {
            Naming.API -> displayName(c.group)
            Naming.REAL -> c.group.trim()
            Naming.DERIVED -> LiveGroups.categoryOf(c.name)
        }

        // Um item por canal, não por qualidade; "Todos" segue a ordem em que cada canal aparece primeiro.
        val byBase = LinkedHashMap<String, MutableList<M3uParser.Channel>>()
        for (c in live) byBase.getOrPut(ChannelQuality.baseName(c.name).lowercase()) { ArrayList() }.add(c)

        val channels = ArrayList<LiveChannel>(byBase.size)
        val members = LinkedHashMap<String, MutableList<Int>>()
        for (variants in byBase.values) {
            val sorted = variants.sortedByDescending { ChannelQuality.rank(it.name) }
            val index = channels.size
            channels.add(toLiveChannel(sorted, categoryOf(sorted.first())))
            // O canal aparece em toda categoria em que alguma qualidade dele está, como no menu da tela de canais.
            sorted.map { categoryOf(it) }.distinct().filter(::isCategoryName).forEach {
                members.getOrPut(it) { ArrayList() }.add(index)
            }
        }

        val preferred = when (naming) {
            Naming.API -> apiOrder.map { displayName(it) }
            Naming.REAL -> members.keys.sortedBy { it.lowercase() }
            Naming.DERIVED -> LiveGroups.ORDER
        }
        val ordered = preferred.filter { it in members }.distinct() + members.keys.filter { it !in preferred }
        return Section(channels, ordered.map { LiveGroup(it, members.getValue(it)) })
    }

    // O painel prefixa as categorias ("TV | Esportes"); o menu da tela de canais mostra só o que vem depois.
    private fun displayName(group: String): String = group.substringAfter(" | ", group).trim()

    // "Todos" e "Favoritos" são linhas do próprio painel: uma categoria do provedor com esse nome não as duplica.
    private fun isCategoryName(name: String) =
        name.isNotEmpty() && !name.equals(LiveGroup.ALL, ignoreCase = true) && !name.equals(LiveGroup.FAVORITES, ignoreCase = true)

    private fun toLiveChannel(sorted: List<M3uParser.Channel>, group: String): LiveChannel {
        val best = sorted.first()
        val many = sorted.takeIf { it.size > 1 }
        return LiveChannel(
            name = ChannelQuality.baseName(best.name),
            url = best.url,
            logo = sorted.firstNotNullOfOrNull { it.logo?.takeIf(String::isNotBlank) }.orEmpty(),
            tvgId = sorted.firstNotNullOfOrNull { it.tvgId?.takeIf(String::isNotBlank) }.orEmpty(),
            apiId = best.apiId,
            group = group,
            userAgent = best.userAgent,
            referer = best.referer,
            variantUrls = many?.map { it.url }.orEmpty(),
            variantLabels = many?.map { ChannelQuality.labelOf(it.name) }.orEmpty(),
        )
    }

    /** "Todos", depois "Favoritos" (só se algum canal ao vivo for favorito) e as categorias do menu. */
    fun groupsOf(section: Section, favorites: Set<String>): List<LiveGroup> = buildList {
        add(LiveGroup(LiveGroup.ALL, section.channels.indices.toList()))
        if (favorites.isNotEmpty()) {
            val fav = section.channels.indices.filter { i -> section.channels[i].allUrls.any { it in favorites } }
            if (fav.isNotEmpty()) add(LiveGroup(LiveGroup.FAVORITES, fav))
        }
        addAll(section.categories)
    }

    /** Categoria em que o painel abre para este canal; "Todos" se ele não tem categoria. */
    fun groupIndexOf(groups: List<LiveGroup>, ch: LiveChannel): Int =
        if (ch.group.isBlank()) 0 else groups.indexOfFirst { it.name == ch.group }.coerceAtLeast(0)

    /** Posição do canal que toca esta URL (a dele ou a de uma de suas qualidades), ou -1. */
    fun indexOfUrl(channels: List<LiveChannel>, url: String): Int =
        channels.indexOfFirst { it.url == url || url in it.variantUrls }

    /** Identificador que sobrevive a atualizações do catálogo: o id do painel e, sem ele, o nome. */
    fun keyOf(ch: LiveChannel): String = if (ch.apiId > 0) "api:${ch.apiId}" else "name:${ch.name.lowercase()}"

    fun indexOfKey(channels: List<LiveChannel>, key: String): Int =
        if (key.isBlank()) -1 else channels.indexOfFirst { keyOf(it) == key }

    // ---------- cola com o catálogo (Android) ----------

    private class Memo(val live: List<M3uParser.Channel>, val order: List<String>, val section: Section)
    @Volatile private var memo: Memo? = null

    /** [build] com lembrança do último resultado: abrir vários canais seguidos não refaz a conta da lista inteira. */
    fun sectionFor(live: List<M3uParser.Channel>, apiOrder: List<String>): Section {
        memo?.let { if (it.live === live && it.order == apiOrder) return it.section }
        return build(live, apiOrder).also { memo = Memo(live, apiOrder, it) }
    }

    /**
     * Monta a [LiveSession] com a seção inteira a partir do que já está em cache (API da conta ou lista M3U na
     * memória) e posiciona no último canal assistido, ou no primeiro. Bloqueia (disco, e rede na primeira vez):
     * chamar fora da UI. Devolve false se não houver como montar, e então quem chamou segue pela tela de canais.
     */
    fun openSession(ctx: Context): Boolean {
        val store = PlaylistStore(ctx)
        val settings = com.seupainel.player.AppSettings(ctx)
        val raw = PlaylistHolder.channels
        val api = if (settings.useXtream) runCatching { XtreamCatalog.load(XtreamCatalog.Kind.LIVE, ctx, store, raw.orEmpty()) }.getOrNull() else null
        val live: List<M3uParser.Channel>
        val order: List<String>
        if (api != null) {
            live = api.channels
            order = api.categoryOrder
        } else {
            live = raw?.filter(::isLive) ?: return false
            order = emptyList()
        }
        if (live.isEmpty()) return false

        val section = sectionFor(live, order)
        val groups = groupsOf(section, store.favorites())
        val last = indexOfKey(section.channels, settings.lastLiveChannel)
        LiveSession.set(section.channels, last.coerceAtLeast(0), groups)
        return true
    }
}
