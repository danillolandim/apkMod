package com.seupainel.player.playlist

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Cliente da API Xtream (`player_api.php`), a mesma que serve o `get.php` da lista M3U.
 * Uma chamada devolve o catálogo inteiro já com data de adição, nota, categoria e
 * `tmdb_id`, o que dispensa descobrir esses dados título a título.
 *
 * A conta (host, usuário, senha) é derivada da URL da lista que o usuário já configurou;
 * nada novo precisa ser digitado nem guardado.
 */
object XtreamApi {

    data class Account(val base: String, val user: String, val pass: String)

    data class Category(val id: String, val name: String)

    data class Vod(
        val id: Long,
        val name: String,
        val tmdbId: Int,
        val icon: String?,
        val rating: Double,      // 0..10; 0 = sem nota
        val added: Long,         // unix, segundos; 0 = desconhecido
        val categoryId: String,
        val ext: String,
        val isAdult: Boolean = false,
    )

    /** `http://host:porta/get.php?username=U&password=P&...` -> conta, ou null se não for Xtream. */
    fun accountFrom(playlistUrl: String?): Account? {
        val url = parseHttp(playlistUrl) ?: return null
        val params = (url.query ?: "").split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i).lowercase() to safeDecode(p.substring(i + 1))
        }.toMap()
        val user = params["username"]?.takeIf { it.isNotBlank() } ?: return null
        val pass = params["password"]?.takeIf { it.isNotBlank() } ?: return null
        return Account(baseOf(url), user, pass)
    }

    /**
     * Conta a partir de um link de stream (`http://host/movie/USUARIO/SENHA/123.mp4`).
     * Serve quando a URL da lista foi digitada de outro jeito (link curto, redirecionamento),
     * mas os itens da lista, que o app precisa tocar, sempre carregam as credenciais.
     */
    fun accountFromStreamUrl(streamUrl: String?): Account? {
        val url = parseHttp(streamUrl) ?: return null
        val seg = url.path.split('/').filter { it.isNotEmpty() }
        if (seg.size < 4 || seg[0].lowercase() !in STREAM_KINDS) return null
        return Account(baseOf(url), safeDecode(seg[1]), safeDecode(seg[2]))
    }

    /** Só o "formato" da URL, sem host nem valores: serve para diagnosticar na tela sem vazar senha. */
    fun shape(url: String?): String {
        if (url.isNullOrBlank()) return "sem URL"
        val u = runCatching { URL(url.trim()) }.getOrNull() ?: return "URL ilegível"
        val file = u.path.substringAfterLast('/')
        val shownFile = if (SAFE_FILE.matches(file)) file else "(outro)"
        val keys = u.query?.split('&')?.map { it.substringBefore('=') }?.filter { it.isNotBlank() }
            ?.joinToString(",")?.ifBlank { null } ?: "nenhum"
        return "${u.protocol}:/…/$shownFile params: $keys"
    }

    // java.net.URI rejeita a URL toda por um caractere especial ou "_" no host; URL é tolerante.
    private fun parseHttp(raw: String?): URL? {
        if (raw.isNullOrBlank()) return null
        val url = runCatching { URL(raw.trim()) }.getOrNull() ?: return null
        val proto = url.protocol?.lowercase()
        if (proto != "http" && proto != "https") return null
        return url.takeIf { it.host.isNotBlank() }
    }

    private fun baseOf(url: URL): String {
        val port = if (url.port > 0) ":${url.port}" else ""
        return "${url.protocol.lowercase()}://${url.host}$port"
    }

    private fun safeDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private val STREAM_KINDS = setOf("live", "movie", "series")
    private val SAFE_FILE = Regex("""(?i)[\w.-]+\.(php|m3u8?|txt)""")

    fun apiUrl(acc: Account, action: String): String =
        "${acc.base}/player_api.php?username=${enc(acc.user)}&password=${enc(acc.pass)}&action=$action"

    fun movieUrl(acc: Account, v: Vod): String =
        "${acc.base}/movie/${acc.user}/${acc.pass}/${v.id}.${v.ext}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- rede ----------------

    fun vodCategories(acc: Account, userAgent: String?): List<Category> =
        fetch(apiUrl(acc, "get_vod_categories"), userAgent) { readCategories(it) }

    fun vodStreams(acc: Account, userAgent: String?): List<Vod> =
        fetch(apiUrl(acc, "get_vod_streams"), userAgent) { readVod(it) }

    /** Baixa direto para um arquivo (o catálogo não passa inteiro pela memória). */
    fun downloadTo(url: String, userAgent: String?, dest: java.io.File) {
        val conn = open(url, userAgent)
        try {
            val raw = if (conn.contentEncoding?.contains("gzip", true) == true)
                GZIPInputStream(conn.inputStream) else conn.inputStream
            raw.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    fun parseVod(file: java.io.File): List<Vod> =
        JsonReader(file.reader(Charsets.UTF_8)).use { readVod(it) }

    fun parseCategories(file: java.io.File): List<Category> =
        JsonReader(file.reader(Charsets.UTF_8)).use { readCategories(it) }

    private fun <T> fetch(url: String, userAgent: String?, parse: (JsonReader) -> T): T {
        val conn = open(url, userAgent)
        try {
            val raw = if (conn.contentEncoding?.contains("gzip", true) == true)
                GZIPInputStream(conn.inputStream) else conn.inputStream
            return JsonReader(InputStreamReader(raw, Charsets.UTF_8)).use(parse)
        } finally {
            conn.disconnect()
        }
    }

    /** Segue redirecionamentos à mão: o provedor pode saltar entre http e https. */
    private fun open(start: String, userAgent: String?): HttpURLConnection {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000          // o catálogo de filmes é grande
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent?.ifBlank { null } ?: DEFAULT_UA)
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = c.responseCode
            if (code in REDIRECTS) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw java.io.IOException("HTTP $code sem destino")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw java.io.IOException("HTTP $code") }
            return c
        }
        throw java.io.IOException("Muitos redirecionamentos")
    }

    // ---------------- parse em streaming ----------------
    // JsonReader não materializa os ~10 MB do catálogo: a TV tem pouca memória.

    internal fun readCategories(r: JsonReader): List<Category> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val out = ArrayList<Category>()
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id: String? = null
            var name: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "category_id" -> id = r.lenientString()
                    "category_name" -> name = r.lenientString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!id.isNullOrBlank() && !name.isNullOrBlank()) out.add(Category(id, name.trim()))
        }
        r.endArray()
        return out
    }

    internal fun readVod(r: JsonReader): List<Vod> {
        // Erro do painel costuma vir como objeto/mensagem, não como lista.
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val out = ArrayList<Vod>(40_000)
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id = 0L; var name: String? = null; var tmdb = 0
            var icon: String? = null; var rating = 0.0; var added = 0L
            var cat = ""; var ext = "mp4"; var adult = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = r.lenientString()?.toLongOrNull() ?: 0L
                    "name" -> name = r.lenientString()
                    "tmdb_id", "tmdb" -> tmdb = r.lenientString()?.toIntOrNull() ?: 0
                    "stream_icon" -> icon = r.lenientString()?.takeIf { it.isNotBlank() }
                    "rating" -> rating = r.lenientString()?.toDoubleOrNull() ?: 0.0
                    "added" -> added = r.lenientString()?.toLongOrNull() ?: 0L
                    "category_id" -> cat = r.lenientString().orEmpty()
                    "container_extension" -> ext = r.lenientString()?.takeIf { it.isNotBlank() } ?: "mp4"
                    "is_adult" -> adult = r.lenientString().let { it == "1" || it.equals("true", true) }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id > 0 && !name.isNullOrBlank())
                out.add(Vod(id, name.trim(), tmdb, icon, rating, added, cat, ext, adult))
        }
        r.endArray()
        return out
    }

    /** O painel mistura string/número/null no mesmo campo ("rating":"5", "tmdb_id":123, "": ""). */
    private fun JsonReader.lenientString(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 6
    // Mesmo UA da lista M3U: alguns painéis filtram por User-Agent.
    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
}
