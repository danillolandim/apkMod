package com.seupainel.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Continuar assistindo": guarda a última posição por conteúdo (chave = URL) e
 * também uma lista dos itens em andamento (com título/capa/tipo) para a fileira
 * "Continuar" na tela de Filmes/Séries. Tudo em SharedPreferences, sem libs.
 */
class ResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    data class Item(
        val key: String,        // URL do conteúdo (chave de posição)
        val title: String,
        val logo: String,
        val kind: String,       // "movie" | "series"
        val pos: Long,
        val dur: Long,
        val ts: Long,
    )

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        // Não guarda se quase no fim (considera "assistido").
        if (durationMs > 0 && positionMs > durationMs - 15_000) {
            prefs.edit().remove(key).apply()
            return
        }
        if (positionMs > 10_000) prefs.edit().putLong(key, positionMs).apply()
    }

    fun get(key: String): Long = prefs.getLong(key, 0L)

    fun clear(key: String) = prefs.edit().remove(key).apply()

    /**
     * Guarda posição E atualiza a fileira "Continuar". Chamado pelo player ao
     * pausar/sair. Remove da fileira quando o conteúdo é concluído.
     */
    fun remember(key: String, title: String, logo: String, kind: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        save(key, positionMs, durationMs)
        val finished = durationMs > 0 && positionMs > durationMs - 15_000
        val list = recents().filterNot { it.key == key }.toMutableList()
        if (!finished && positionMs > 10_000 && title.isNotBlank()) {
            list.add(0, Item(key, title, logo, kind, positionMs, durationMs, System.currentTimeMillis()))
        }
        saveRecents(list.take(MAX_RECENTS))
    }

    /** Itens em andamento, mais recentes primeiro; opcionalmente só de um tipo. */
    fun recents(kind: String? = null): List<Item> {
        val raw = prefs.getString(RECENTS_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val it = Item(
                    key = o.optString("k"), title = o.optString("t"), logo = o.optString("l"),
                    kind = o.optString("kind", "movie"),
                    pos = o.optLong("p"), dur = o.optLong("d"), ts = o.optLong("ts"),
                )
                if (it.key.isNotBlank() && (kind == null || it.kind == kind)) out.add(it)
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    fun removeRecent(key: String) {
        saveRecents(recents().filterNot { it.key == key })
    }

    /** Progresso de um item em andamento, 1..99% (0 = não começado/sem dados). */
    fun progress(key: String): Int {
        val it = recents().firstOrNull { it.key == key } ?: return 0
        if (it.dur <= 0) return 0
        return ((it.pos * 100) / it.dur).toInt().coerceIn(1, 99)
    }

    // ---- "assistido" (episódios de série) ----
    fun isWatched(key: String): Boolean =
        key.isNotBlank() && prefs.getStringSet(WATCHED_KEY, emptySet())?.contains(key) == true

    fun markWatched(key: String) {
        if (key.isBlank()) return
        val s = HashSet(prefs.getStringSet(WATCHED_KEY, emptySet()) ?: emptySet())
        if (s.add(key)) prefs.edit().putStringSet(WATCHED_KEY, s).apply()
    }

    fun unmarkWatched(key: String) {
        val s = HashSet(prefs.getStringSet(WATCHED_KEY, emptySet()) ?: emptySet())
        if (s.remove(key)) prefs.edit().putStringSet(WATCHED_KEY, s).apply()
    }

    /** Alterna e devolve o novo estado (true = assistido). */
    fun toggleWatched(key: String): Boolean =
        if (isWatched(key)) { unmarkWatched(key); false } else { markWatched(key); true }

    private fun saveRecents(list: List<Item>) {
        val arr = JSONArray()
        for (it in list) {
            arr.put(JSONObject().apply {
                put("k", it.key); put("t", it.title); put("l", it.logo)
                put("kind", it.kind); put("p", it.pos); put("d", it.dur); put("ts", it.ts)
            })
        }
        prefs.edit().putString(RECENTS_KEY, arr.toString()).apply()
    }

    companion object {
        private const val RECENTS_KEY = "recents"
        private const val WATCHED_KEY = "watched"
        private const val MAX_RECENTS = 24
    }
}
