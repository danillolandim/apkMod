package com.seupainel.player.meta

import com.seupainel.player.playlist.XtreamApi
import java.util.concurrent.Executors

/**
 * Cache em memória da programação por canal. Um canal tem no máximo uma consulta em andamento; o resultado vale
 * [ttlMs] (e [failTtlMs] quando a consulta falhou, para não martelar o servidor). O dado antigo continua legível
 * depois de vencido: a tela segue mostrando o que tem enquanto a consulta nova não chega.
 */
class ProgramCache(
    private val ttlMs: Long,
    private val failTtlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Entry(val programs: List<Epg.Prog>, val at: Long, val ok: Boolean)

    private val entries = HashMap<Long, Entry>()
    private val fetching = HashSet<Long>()

    @Synchronized fun entry(key: Long): Entry? = entries[key]

    @Synchronized fun isFetching(key: Long): Boolean = key in fetching

    @Synchronized fun isFresh(key: Long): Boolean = entries[key]?.let { clock() - it.at < if (it.ok) ttlMs else failTtlMs } ?: false

    /** true = quem chamou deve consultar agora (e já ficou marcado como em andamento); false = fresco ou já em curso. */
    @Synchronized fun tryBegin(key: Long): Boolean {
        if (key in fetching || isFresh(key)) return false
        fetching.add(key)
        return true
    }

    /** Guarda o resultado. Falha não apaga o dado bom que já existia. */
    @Synchronized fun finish(key: Long, programs: List<Epg.Prog>, ok: Boolean) {
        fetching.remove(key)
        val previous = entries[key]
        entries[key] = if (ok || previous == null) Entry(programs, clock(), ok) else Entry(previous.programs, clock(), ok = false)
    }

    @Synchronized fun clear() {
        entries.clear()
        fetching.clear()
    }
}

/**
 * Fonte única da programação de um canal ao vivo, para a faixa do canal e para a lista não precisarem saber de onde
 * ela vem: o XMLTV (se há URL em Configurações e o arquivo traz o canal) ou a API da conta, consultada sob demanda
 * (`get_short_epg`) e guardada por [TTL_MS].
 */
object Guide {

    enum class Phase { READY, LOADING, NONE }

    class Listing(val agenda: Epg.Agenda, val phase: Phase)

    private const val TTL_MS = 10 * 60_000L
    private const val FAIL_TTL_MS = 60_000L
    private const val FETCH_LIMIT = 6   // o atual e mais cinco: a lista mostra o atual e os 3 seguintes

    private val cache = ProgramCache(TTL_MS, FAIL_TTL_MS)
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "guide-fetch").apply { isDaemon = true } }
    private val empty = Epg.Agenda(null, emptyList())

    @Volatile private var account: XtreamApi.Account? = null
    @Volatile private var userAgent: String? = null

    /** Chamado (fora da thread principal) quando uma consulta termina: a tela se redesenha. */
    @Volatile var listener: (() -> Unit)? = null

    /** [acc] nulo = sem conta Xtream: só o XMLTV. Trocar de conta esquece o que veio da anterior. */
    fun configure(acc: XtreamApi.Account?, ua: String?) {
        if (acc != account) cache.clear()
        account = acc
        userAgent = ua
    }

    /** O que se sabe agora, sem pedir nada à rede. [upcoming] = quantos programas depois do atual. */
    fun peek(tvgId: String?, apiId: Long, upcoming: Int, now: Long = System.currentTimeMillis()): Listing =
        resolve(
            xml = Epg.listFor(tvgId),
            cached = if (apiId > 0) cache.entry(apiId) else null,
            canFetch = account != null && apiId > 0,
            fresh = apiId > 0 && cache.isFresh(apiId),
            upcoming = upcoming,
            now = now,
        )

    /**
     * Pede à API da conta a programação do canal, se o XMLTV não a tem e o cache venceu. Não bloqueia; o resultado
     * chega em [listener]. Chamar só para o canal que o usuário de fato está vendo ou focando, não por linha.
     */
    fun request(tvgId: String?, apiId: Long, now: Long = System.currentTimeMillis()) {
        val acc = account ?: return
        if (apiId <= 0 || hasUseful(Epg.listFor(tvgId), now)) return
        if (!cache.tryBegin(apiId)) return
        val ua = userAgent
        executor.execute {
            try {
                cache.finish(apiId, XtreamApi.shortEpg(acc, ua, apiId, FETCH_LIMIT), ok = true)
            } catch (_: Exception) {
                cache.finish(apiId, emptyList(), ok = false)
            }
            listener?.invoke()
        }
    }

    /** "20:00  Jornal" do programa atual quando já se sabe (XMLTV ou cache); "" caso contrário. */
    fun nowText(tvgId: String?, apiId: Long): String {
        val cur = peek(tvgId, apiId, upcoming = 0).agenda.current ?: return ""
        return "${Epg.hhmm(cur.start)}  ${cur.title}"
    }

    /** Linha do programa atual para a faixa e a lista, com os estados de carregando e sem informação. */
    fun nowLine(l: Listing): String {
        val cur = l.agenda.current
        return when {
            cur != null -> "${Epg.hhmm(cur.start)}–${Epg.hhmm(cur.stop)}  ${cur.title}"
            l.agenda.upcoming.isNotEmpty() -> "Sem informação do programa atual"
            l.phase == Phase.LOADING -> "Carregando programação…"
            else -> "Sem informação de programação"
        }
    }

    internal fun resolve(
        xml: List<Epg.Prog>?, cached: ProgramCache.Entry?, canFetch: Boolean, fresh: Boolean, upcoming: Int, now: Long,
    ): Listing {
        for (list in listOfNotNull(xml, cached?.programs)) {
            val agenda = Epg.agendaIn(list, now, upcoming)
            if (agenda.current != null || agenda.upcoming.isNotEmpty()) return Listing(agenda, Phase.READY)
        }
        return Listing(empty, if (canFetch && !fresh) Phase.LOADING else Phase.NONE)
    }

    private fun hasUseful(list: List<Epg.Prog>?, now: Long): Boolean {
        if (list == null) return false
        val agenda = Epg.agendaIn(list, now, 1)
        return agenda.current != null || agenda.upcoming.isNotEmpty()
    }
}
