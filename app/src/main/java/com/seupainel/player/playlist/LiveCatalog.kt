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

    /**
     * Categoria em que o zapping (CH+/CH−) anda: a primeira de [wanted] (nomes, por ordem de preferência) que
     * existe e contém o canal; sem nenhuma, a do próprio canal. Um canal com qualidades em categorias diferentes
     * (4K numa, HD noutra) continua na categoria por onde a pessoa chegou, e não na da melhor qualidade.
     */
    fun zapGroupOf(groups: List<LiveGroup>, channels: List<LiveChannel>, index: Int, wanted: List<String>): Int {
        for (name in wanted) {
            if (name.isBlank()) continue
            val g = groups.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
            if (g >= 0 && groups[g].positionOf(index) >= 0) return g
        }
        return channels.getOrNull(index)?.let { groupIndexOf(groups, it) } ?: 0
    }

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

    /** Resultado de abrir a TV ao vivo direto: a sessão pronta, ou o motivo (curto, em português, sem endereço nem conta). */
    sealed class Opening {
        class Ready(val section: Section, val groups: List<LiveGroup>, val startIndex: Int) : Opening() {
            val first: LiveChannel get() = section.channels[startIndex]

            /** Entrega a seção ao player. Fica para o último instante (na thread da tela): nada limpa a sessão no meio. */
            fun publish() = LiveSession.set(section.channels, startIndex, groups)
        }
        class Failed(reason: String) : Opening() {
            val reason: String = scrub(reason)
        }
    }

    /**
     * Escolhe a fonte e monta a seção, sem tocar em Android. [api] é o catálogo ao vivo da conta (null se desligado
     * ou se falhou, com o motivo em [apiNote]); [raw] é a lista M3U em memória (null enquanto não carregou).
     * [preloadDone]: o app já terminou de carregar o que tinha em disco; se não, a falha provavelmente é só pressa.
     */
    internal fun assemble(
        useXtream: Boolean,
        api: XtreamCatalog.Section?,
        apiNote: String?,
        raw: List<M3uParser.Channel>?,
        preloadDone: Boolean,
        favorites: Set<String>,
        lastKey: String,
    ): Opening {
        val live: List<M3uParser.Channel>
        val order: List<String>
        if (api != null) {
            live = api.channels
            order = api.categoryOrder
        } else if (raw != null) {
            live = raw.filter(::isLive)
            order = emptyList()
        } else {
            val why = if (!useXtream) "API desligada nas Configurações e a lista M3U não está carregada"
                else "catálogo da API indisponível${apiNote?.let { " ($it)" }.orEmpty()} e a lista M3U não está carregada"
            return Opening.Failed(why + if (preloadDone) "" else "; o app ainda está carregando, tente de novo em instantes")
        }
        if (live.isEmpty()) return Opening.Failed(if (api != null) "a API não trouxe canais ao vivo" else "a lista M3U não tem canais ao vivo")

        val section = sectionFor(live, order)
        val groups = groupsOf(section, favorites)
        return Opening.Ready(section, groups, indexOfKey(section.channels, lastKey).coerceAtLeast(0))
    }

    /** Tira da mensagem qualquer coisa com cara de endereço: o motivo vai para a tela e nunca pode vazar a conta. */
    internal fun scrub(text: String): String = text.replace(Regex("""(?i)\b[a-z][a-z0-9+.\-]*://\S*"""), "[endereço]")

    /** Espera [done] ficar true por até [maxMs], conferindo a cada [stepMs]. Devolve o valor final. */
    internal fun waitUntil(
        maxMs: Long, stepMs: Long, done: () -> Boolean,
        now: () -> Long = System::currentTimeMillis, sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        val deadline = now() + maxMs
        while (!done()) {
            if (now() >= deadline) return false
            sleep(stepMs)
        }
        return true
    }

    private const val PRELOAD_WAIT_MS = 30_000L

    /**
     * Monta a seção inteira a partir do que já está em cache (API da conta ou lista M3U na memória), posicionada no
     * último canal assistido, ou no primeiro; quem abre o player chama [Opening.Ready.publish]. Bloqueia (espera o carregamento da abertura,
     * disco e, na primeira vez, rede): chamar fora da UI. Nunca lança: a falha volta como [Opening.Failed].
     */
    fun openSession(ctx: Context): Opening = try {
        // Clicar na Home antes de o app terminar de carregar a lista: sem ela a conta pode nem ser derivável.
        try {
            waitUntil(PRELOAD_WAIT_MS, 100, done = { PlaylistHolder.preloadDone })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val store = PlaylistStore(ctx)
        val settings = com.seupainel.player.AppSettings(ctx)
        val raw = PlaylistHolder.channels
        var note: String? = null
        var api: XtreamCatalog.Section? = null
        if (settings.useXtream) {
            try {
                api = XtreamCatalog.load(XtreamCatalog.Kind.LIVE, ctx, store, raw.orEmpty())
                if (api == null) note = XtreamCatalog.statusOf(XtreamCatalog.Kind.LIVE)
            } catch (e: Exception) {
                note = "erro: ${XtreamApi.describe(e)}"
            }
        }
        assemble(settings.useXtream, api, note, raw, PlaylistHolder.preloadDone, store.favorites(), settings.lastLiveChannel)
    } catch (e: Throwable) {
        Opening.Failed("erro ao montar a lista de canais (${XtreamApi.describe(e)})")
    }
}
