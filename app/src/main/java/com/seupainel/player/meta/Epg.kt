package com.seupainel.player.meta

import android.util.Xml
import com.seupainel.player.net.Net
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Guia de programação (EPG) a partir de um arquivo XMLTV.
 *
 * Lê a URL de EPG das Configurações (pode ser .xml ou .xml.gz), guarda só a
 * janela de interesse (de ~2h atrás até ~2 dias à frente) para não estourar a
 * memória, e responde "agora / a seguir" por tvg-id. Carrega uma vez em 2º
 * plano; as telas consultam o que já estiver pronto.
 */
object Epg {

    data class Prog(val start: Long, val stop: Long, val title: String)

    @Volatile var loaded = false; private set
    @Volatile private var loading = false
    @Volatile private var byId: Map<String, List<Prog>> = emptyMap()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Registra um callback chamado (na thread de carga) quando o EPG ficar pronto. */
    fun onReady(cb: () -> Unit) {
        if (loaded) cb() else listeners.add(cb)
    }

    /** Carrega o XMLTV uma vez. Chamável várias vezes: só a 1ª faz trabalho. */
    fun ensureLoaded(url: String?, userAgent: String?) {
        if (url.isNullOrBlank() || loaded || loading) return
        loading = true
        Thread({
            try {
                byId = fetchAndParse(url, userAgent)
                loaded = true
                listeners.forEach { runCatching { it() } }
                listeners.clear()
            } catch (_: Throwable) {
                // EPG é opcional: falha silenciosa, o app segue sem guia.
            } finally {
                loading = false
            }
        }, "epg-load").start()
    }

    /** (agora, a seguir) para um tvg-id, ou (null, null) se não houver dados. */
    fun nowNext(tvgId: String?): Pair<Prog?, Prog?> {
        if (tvgId.isNullOrBlank() || !loaded) return null to null
        val id = tvgId.trim()
        val list = byId[id] ?: byId[id.lowercase()] ?: return null to null
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val p = list[i]
            if (now in p.start until p.stop) return p to list.getOrNull(i + 1)
            if (p.start > now) return null to p   // intervalo sem programa atual
        }
        return null to null
    }

    /** Texto para a barra do canal: "20:00 Jornal • A seguir 20:30 Novela". */
    fun barText(tvgId: String?): String {
        val (cur, nxt) = nowNext(tvgId)
        if (cur == null && nxt == null) return ""
        val sb = StringBuilder()
        if (cur != null) sb.append(hhmm(cur.start)).append("  ").append(cur.title)
        if (nxt != null) {
            if (sb.isNotEmpty()) sb.append("   •   ")
            sb.append("A seguir ").append(hhmm(nxt.start)).append("  ").append(nxt.title)
        }
        return sb.toString()
    }

    /** Só o programa atual (para a lista de canais), ou "" se não houver. */
    fun nowText(tvgId: String?): String {
        val cur = nowNext(tvgId).first ?: return ""
        return "${hhmm(cur.start)}  ${cur.title}"
    }

    // ---------------- interno ----------------

    private val hhmmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun hhmm(ms: Long): String = synchronized(hhmmFmt) { hhmmFmt.format(ms) }

    private fun fetchAndParse(url: String, userAgent: String?): Map<String, List<Prog>> {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent?.ifBlank { null } ?: "okhttp")
            .build()
        Net.client.newCall(req).execute().use { resp ->
            val body = resp.body ?: return emptyMap()
            val raw = body.byteStream().buffered()
            val gz = url.lowercase().let { it.endsWith(".gz") || it.endsWith(".gzip") } || looksGzip(raw)
            val input: InputStream = if (gz) GZIPInputStream(raw) else raw
            return parse(input)
        }
    }

    /** Detecta gzip pela assinatura (0x1f 0x8b) sem consumir o stream. */
    private fun looksGzip(buffered: java.io.BufferedInputStream): Boolean {
        buffered.mark(2)
        val b0 = buffered.read(); val b1 = buffered.read()
        buffered.reset()
        return b0 == 0x1f && b1 == 0x8b
    }

    private fun parse(input: InputStream): Map<String, List<Prog>> {
        val now = System.currentTimeMillis()
        val lo = now - 2L * 3600_000          // 2h atrás
        val hi = now + 48L * 3600_000         // 2 dias à frente
        val tmp = HashMap<String, ArrayList<Prog>>()
        input.use {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(it, null)
            var ev = parser.eventType
            var chId: String? = null
            var start = 0L; var stop = 0L
            var inProg = false
            var title: String? = null
            var inTitle = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "programme" -> {
                            inProg = true; title = null
                            chId = parser.getAttributeValue(null, "channel")?.trim()
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                        }
                        "title" -> if (inProg) inTitle = true
                    }
                    XmlPullParser.TEXT -> if (inTitle && title == null) title = parser.text?.trim()
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "programme" -> {
                            inProg = false
                            val id = chId; val t = title
                            if (id != null && !t.isNullOrBlank() && stop > lo && start < hi) {
                                tmp.getOrPut(id) { ArrayList() }.add(Prog(start, stop, t))
                            }
                        }
                    }
                }
                ev = parser.next()
            }
        }
        // Ordena cada canal por início; chaves em minúsculas também (fallback).
        val out = HashMap<String, List<Prog>>(tmp.size * 2)
        for ((id, list) in tmp) {
            val sorted = list.sortedBy { it.start }
            out[id] = sorted
            out[id.lowercase()] = sorted
        }
        return out
    }

    private val tzFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val plainFmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    private fun parseTime(s: String?): Long {
        val t = s?.trim() ?: return 0
        if (t.length < 14) return 0
        return try {
            if (t.length > 15) synchronized(tzFmt) { tzFmt.parse(t)?.time ?: 0 }
            else synchronized(plainFmt) { plainFmt.parse(t.take(14))?.time ?: 0 }
        } catch (_: Throwable) { 0 }
    }
}
