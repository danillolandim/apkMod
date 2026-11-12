package com.seupainel.player

/**
 * Regras de "assistido" sem Android (testadas em JVM): contagem por temporada, próximo episódio, estado de cada
 * episódio, rótulos do botão principal e selos da grade. Quem lê e grava o que foi visto é o [ResumeStore].
 *
 * Um episódio com progresso guardado está "em andamento" mesmo que já tenha sido marcado como visto antes (alguém
 * que revê um episódio): o ponto salvo é o que o botão "Continuar" retoma, e a contagem de vistos o deixa de fora
 * até terminar de novo.
 */
object WatchLogic {

    data class Ep(val season: Int, val episode: Int, val key: String)

    enum class State { WATCHED, IN_PROGRESS, NEXT, UNWATCHED }

    data class SeasonCount(val season: Int, val watched: Int, val total: Int) {
        val complete: Boolean get() = total > 0 && watched >= total
    }

    enum class PickKind { START, RESUME, NEXT, REWATCH }

    data class Pick(val ep: Ep, val kind: PickKind)

    private val ORDER = compareBy<Ep>({ it.season }, { it.episode })

    fun seasonCounts(eps: List<Ep>, isWatched: (String) -> Boolean): List<SeasonCount> =
        eps.groupBy { it.season }.toSortedMap().map { (season, list) ->
            SeasonCount(season, list.count { isWatched(it.key) }, list.size)
        }

    fun seasonLabel(c: SeasonCount) = "T${c.season} · ${c.watched}/${c.total}"

    fun seasonDescription(c: SeasonCount) =
        if (c.complete) "Temporada ${c.season}, concluída, ${c.watched} de ${c.total} episódios"
        else "Temporada ${c.season}, ${c.watched} de ${c.total} episódios vistos"

    fun summary(watched: Int, total: Int): String = when {
        total <= 0 -> ""
        watched >= total -> if (total == 1) "Você viu o único episódio" else "Você viu todos os $total episódios"
        watched <= 0 -> "Você ainda não começou · $total ${if (total == 1) "episódio" else "episódios"}"
        else -> "Você viu $watched de $total episódios"
    }

    /**
     * Para onde o botão principal leva. 1) o episódio em andamento (o mexido por último); 2) o seguinte ao último
     * visto; 3) o primeiro, se nada foi visto; 4) um episódio que ficou para trás; 5) tudo visto: rever desde o início.
     * [progressPct] é 0 quando não há ponto salvo; [touchedAt] desempata episódios em andamento.
     */
    fun pickNext(
        eps: List<Ep>,
        isWatched: (String) -> Boolean,
        progressPct: (String) -> Int,
        touchedAt: (String) -> Long,
    ): Pick? {
        if (eps.isEmpty()) return null
        val ordered = eps.sortedWith(ORDER)
        ordered.filter { progressPct(it.key) > 0 }
            .maxWithOrNull(compareBy<Ep> { touchedAt(it.key) }.then(ORDER))
            ?.let { return Pick(it, PickKind.RESUME) }
        val lastWatched = ordered.indexOfLast { isWatched(it.key) }
        if (lastWatched < 0) return Pick(ordered.first(), PickKind.START)
        ordered.getOrNull(lastWatched + 1)?.let { return Pick(it, PickKind.NEXT) }
        ordered.firstOrNull { !isWatched(it.key) }?.let { return Pick(it, PickKind.NEXT) }
        return Pick(ordered.first(), PickKind.REWATCH)
    }

    fun primaryLabel(p: Pick): String {
        val at = "T${p.ep.season} · E${p.ep.episode}"
        return when (p.kind) {
            PickKind.START -> "Assistir $at"
            PickKind.RESUME, PickKind.NEXT -> "Continuar $at"
            PickKind.REWATCH -> "Rever $at"
        }
    }

    /** Só o episódio que o botão leva (e que ainda não começou) ganha o destaque "A seguir". */
    fun isHighlighted(pick: Pick?, ep: Ep): Boolean =
        pick != null && pick.ep.key == ep.key && (pick.kind == PickKind.START || pick.kind == PickKind.NEXT)

    fun stateOf(watched: Boolean, progressPct: Int, isNext: Boolean): State = when {
        progressPct > 0 -> State.IN_PROGRESS
        watched -> State.WATCHED
        isNext -> State.NEXT
        else -> State.UNWATCHED
    }

    /** O que o leitor de tela fala do episódio: o estado nunca fica só na cor. */
    fun episodeDescription(number: Int, title: String, state: State, remaining: String?): String {
        val status = when (state) {
            State.WATCHED -> "assistido"
            State.IN_PROGRESS -> listOfNotNull("em andamento", remaining?.replaceFirstChar { it.lowercase() }).joinToString(", ")
            State.NEXT -> "a seguir"
            State.UNWATCHED -> "não assistido"
        }
        return listOfNotNull("Episódio $number", title.ifBlank { null }, status).joinToString(", ")
    }

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "Restam 12 min" (arredonda para cima), ou null se não dá para saber. */
    fun remainingText(positionMs: Long, durationMs: Long): String? {
        val left = durationMs - positionMs
        if (durationMs <= 0 || left <= 0) return null
        if (left < 60_000) return "Resta menos de 1 min"
        return "Restam ${(left + 59_999) / 60_000} min"
    }

    // ---------- filme ----------

    enum class MovieKind { WATCH, RESUME, REWATCH }

    data class MovieAction(val kind: MovieKind, val label: String)

    /** O ponto salvo manda: filme visto que foi revisto pela metade volta a oferecer "Continuar de…". */
    fun movieAction(positionMs: Long, durationMs: Long, watched: Boolean): MovieAction = when {
        positionMs > 0 -> MovieAction(MovieKind.RESUME, "Continuar de ${clock(positionMs)}")
        watched -> MovieAction(MovieKind.REWATCH, "Rever")
        else -> MovieAction(MovieKind.WATCH, "Assistir")
    }

    // ---------- selos da grade ----------

    /** [watched] = selo de assistido; [progressPct] = barra fina (0 = sem barra). Um só dos dois por vez. */
    data class Mark(val watched: Boolean, val progressPct: Int)

    fun movieMark(watched: Boolean, progressPct: Int): Mark =
        if (progressPct > 0) Mark(false, progressPct) else Mark(watched, 0)

    fun seriesMark(watched: Int, total: Int): Mark = when {
        total <= 0 || watched <= 0 -> Mark(false, 0)
        watched >= total -> Mark(true, 0)
        else -> Mark(false, (watched * 100 / total).coerceIn(1, 99))
    }

    /** Chave do resumo guardado por série: o id do painel quando há, senão o título. */
    fun seriesKey(apiId: Long, title: String): String =
        if (apiId > 0) "api:$apiId" else "t:${title.trim().lowercase()}"
}
