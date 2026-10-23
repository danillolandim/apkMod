package com.seupainel.player.playlist

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Detecta automaticamente o que ENTROU DE NOVO na lista, comparando cada
 * atualização com a versão anterior.
 *
 * Dois arquivos:
 *  - known_items.txt : todas as URLs já conhecidas (usado só na comparação,
 *    em 2º plano quando uma lista nova é baixada);
 *  - new_items.json  : as novidades {url -> quando apareceu}, pequeno e lido
 *    rápido para montar/filtrar a categoria "Novidades".
 */
class NewItems(context: Context) {

    private val appCtx = context.applicationContext
    private val knownFile = File(appCtx.filesDir, "known_items.txt")
    private val newFile = File(appCtx.filesDir, "new_items.json")

    private val newMap = ConcurrentHashMap<String, Long>()   // novidade -> timestamp
    @Volatile private var newLoaded = false

    private fun ensureNew() {
        if (newLoaded) return
        synchronized(this) { if (!newLoaded) { loadNew(); newLoaded = true } }
    }

    /** Chamado em 2º plano quando uma lista NOVA é baixada (não do cache). */
    fun update(currentUrls: Collection<String>) {
        ensureNew()
        val known = loadKnown()
        val firstRun = known.isEmpty()
        val cur = currentUrls.toHashSet()
        val now = System.currentTimeMillis()

        if (!firstRun) {
            for (u in cur) if (u !in known) newMap[u] = now     // entrou agora
        }
        // expira novidades antigas e as que saíram da lista
        val cutoff = now - WINDOW_MS
        newMap.entries.removeAll { (u, ts) -> ts < cutoff || u !in cur }

        saveNew()
        saveKnown(cur)
    }

    fun isNew(url: String): Boolean { ensureNew(); return newMap.containsKey(url) }
    fun firstSeen(url: String): Long { ensureNew(); return newMap[url] ?: 0L }
    fun anyNew(): Boolean { ensureNew(); return newMap.isNotEmpty() }
    fun count(): Int { ensureNew(); return newMap.size }

    // -------- persistência --------

    private fun loadKnown(): Set<String> =
        runCatching {
            if (!knownFile.exists()) emptySet()
            else knownFile.readLines().filter { it.isNotBlank() }.toHashSet()
        }.getOrDefault(emptySet())

    private fun saveKnown(urls: Set<String>) {
        runCatching { knownFile.writeText(urls.joinToString("\n")) }
    }

    private fun loadNew() {
        runCatching {
            if (!newFile.exists()) return
            val root = JSONObject(newFile.readText())
            for (k in root.keys()) newMap[k] = root.optLong(k)
        }
    }

    private fun saveNew() {
        runCatching {
            val root = JSONObject()
            newMap.forEach { (k, v) -> root.put(k, v) }
            newFile.writeText(root.toString())
        }
    }

    companion object {
        private const val WINDOW_MS = 21L * 24 * 60 * 60 * 1000   // novidades por 21 dias
    }
}
