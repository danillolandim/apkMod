package com.seupainel.player.playlist

import android.util.JsonReader
import android.util.JsonToken
import com.seupainel.player.net.Net
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    data class SeriesRow(
        val id: Long,
        val name: String,
        val cover: String?,
        val rating: Double,
        val modified: Long,      // last_modified (unix, s)
        val categoryId: String,
        val tmdbId: Int,
    )

    data class LiveRow(
        val id: Long,
        val name: String,
        val icon: String?,
        val epgId: String?,
        val added: Long,
        val categoryId: String,
        val isAdult: Boolean,
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
    // Cliente do app: DNS do sistema com DNS-over-HTTPS de reserva, segue http<->https e
    // descompacta gzip. Leitura mais longa: o painel pode demorar para montar o catálogo todo.
    private val http: OkHttpClient by lazy {
        Net.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    }

    private fun request(url: String, userAgent: String?) =
        Request.Builder().url(url).header("User-Agent", userAgent?.ifBlank { null } ?: DEFAULT_UA).build()

    /** Baixa direto para um arquivo (o catálogo não passa inteiro pela memória). */
    fun downloadTo(url: String, userAgent: String?, dest: java.io.File) {
        http.newCall(request(url, userAgent)).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("Resposta vazia")
            body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }

    fun parseVod(file: java.io.File): List<Vod> =
        JsonReader(file.reader(Charsets.UTF_8)).use { readVod(it) }

    fun parseCategories(file: java.io.File): List<Category> =
        JsonReader(file.reader(Charsets.UTF_8)).use { readCategories(it) }

    fun parseSeries(file: java.io.File): List<SeriesRow> =
        JsonReader(file.reader(Charsets.UTF_8)).use { readSeries(it) }

    fun parseLive(file: java.io.File): List<LiveRow> =
        JsonReader(file.reader(Charsets.UTF_8)).use { readLive(it) }

    /**
     * Episódios de uma série (`get_series_info`), buscados ao abrir a série: o catálogo traz só
     * as séries, não os milhares de episódios. O painel devolve "episodes" como objeto por
     * temporada ({"1":[...]}) ou, às vezes, como lista de listas; os dois formatos são aceitos.
     */
    fun seriesEpisodes(
        acc: Account, userAgent: String?, seriesId: Long, seriesTitle: String, logo: String?,
    ): List<SeriesGrouper.Ep> {
        val text = http.newCall(request(apiUrl(acc, "get_series_info") + "&series_id=$seriesId", userAgent))
            .execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        val eps = JSONObject(text).opt("episodes")
        val out = ArrayList<SeriesGrouper.Ep>()
        fun addSeason(arr: JSONArray?, seasonHint: Int) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val season = o.optInt("season", 0).takeIf { it > 0 } ?: seasonHint
                val num = o.optInt("episode_num", i + 1)
                val ext = o.optString("container_extension").ifBlank { "mp4" }
                val ch = M3uParser.Channel(
                    name = "%s S%02dE%02d".format(seriesTitle, season, num),
                    url = "${acc.base}/series/${acc.user}/${acc.pass}/$id.$ext",
                    group = "", logo = logo, duration = 0,
                )
                out.add(SeriesGrouper.Ep(season, num, ch, false))
            }
        }
        when (eps) {
            is JSONObject -> eps.keys().forEach { k -> addSeason(eps.optJSONArray(k), k.toIntOrNull() ?: 1) }
            is JSONArray -> for (i in 0 until eps.length()) addSeason(eps.optJSONArray(i), i + 1)
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Nome curto da falha, sem a mensagem: algumas exceções trazem a URL (com a senha). */
    fun describe(e: Throwable): String =
        if (e is java.io.IOException && e.message?.startsWith("HTTP") == true) e.message.orEmpty()
        else e.javaClass.simpleName

    /** Relatório para a tela de Configurações. Nunca inclui host, usuário nem senha. */
    fun diagnose(acc: Account, userAgent: String?): String {
        val host = runCatching { URL(acc.base).host }.getOrDefault("")
        fun dns(lookup: () -> Any) = if (runCatching { lookup() }.isSuccess) "ok" else "FALHOU"
        return buildString {
            appendLine("DNS do sistema: " + dns { InetAddress.getByName(host) })
            appendLine("DNS do app (com DoH): " + dns { Net.client.dns.lookup(host) })
            appendLine(probe("Categorias", apiUrl(acc, "get_vod_categories"), userAgent))
            appendLine(probe("Filmes", apiUrl(acc, "get_vod_streams"), userAgent))
            appendLine(probe("Séries", apiUrl(acc, "get_series"), userAgent))
            append(probe("TV", apiUrl(acc, "get_live_streams"), userAgent))
        }
    }

    private fun probe(label: String, url: String, userAgent: String?): String {
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0
        return try {
            http.newCall(request(url, userAgent)).execute().use { r ->
                val type = r.header("Content-Type")?.substringBefore(';') ?: "?"
                var bytes = 0L
                var first = ' '
                r.body?.byteStream()?.use { s ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        val n = s.read(buf)
                        if (n <= 0) break
                        if (bytes == 0L) first = firstChar(buf, n)
                        bytes += n
                    }
                }
                "$label: HTTP ${r.code}, ${bytes / 1024} KB em ${ms()} ms, começa com '$first', $type"
            }
        } catch (e: Exception) {
            "$label: falhou (${describe(e)}) após ${ms()} ms"
        }
    }

    private fun firstChar(b: ByteArray, n: Int): Char {
        var i = 0
        if (n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
        while (i < n && b[i].toInt().toChar().isWhitespace()) i++
        return if (i < n) b[i].toInt().toChar() else ' '
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

    internal fun readSeries(r: JsonReader): List<SeriesRow> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val out = ArrayList<SeriesRow>(6_000)
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id = 0L; var name: String? = null; var cover: String? = null; var rating = 0.0
            var modified = 0L; var cat = ""; var tmdb = 0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "series_id" -> id = r.lenientString()?.toLongOrNull() ?: 0L
                    "name" -> name = r.lenientString()
                    "cover" -> cover = r.lenientString()?.takeIf { it.isNotBlank() }
                    "rating" -> rating = r.lenientString()?.toDoubleOrNull() ?: 0.0
                    "last_modified" -> modified = r.lenientString()?.toLongOrNull() ?: 0L
                    "category_id" -> cat = r.lenientString().orEmpty()
                    "category_ids" -> if (cat.isEmpty()) cat = r.firstOfArray() else r.skipValue()
                    "tmdb", "tmdb_id" -> tmdb = r.lenientString()?.toIntOrNull() ?: 0
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id > 0 && !name.isNullOrBlank()) out.add(SeriesRow(id, name.trim(), cover, rating, modified, cat, tmdb))
        }
        r.endArray()
        return out
    }

    internal fun readLive(r: JsonReader): List<LiveRow> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val out = ArrayList<LiveRow>(12_000)
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id = 0L; var name: String? = null; var icon: String? = null; var epg: String? = null
            var added = 0L; var cat = ""; var adult = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = r.lenientString()?.toLongOrNull() ?: 0L
                    "name" -> name = r.lenientString()
                    "stream_icon" -> icon = r.lenientString()?.takeIf { it.isNotBlank() }
                    "epg_channel_id" -> epg = r.lenientString()?.takeIf { it.isNotBlank() }
                    "added" -> added = r.lenientString()?.toLongOrNull() ?: 0L
                    "category_id" -> cat = r.lenientString().orEmpty()
                    "category_ids" -> if (cat.isEmpty()) cat = r.firstOfArray() else r.skipValue()
                    "is_adult" -> adult = r.lenientString().let { it == "1" || it.equals("true", true) }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id > 0 && !name.isNullOrBlank()) out.add(LiveRow(id, name.trim(), icon, epg, added, cat, adult))
        }
        r.endArray()
        return out
    }

    private fun JsonReader.firstOfArray(): String {
        if (peek() != JsonToken.BEGIN_ARRAY) { skipValue(); return "" }
        var first = ""
        beginArray()
        while (hasNext()) { val v = lenientString().orEmpty(); if (first.isEmpty()) first = v }
        endArray()
        return first
    }

    /** O painel mistura string/número/null no mesmo campo ("rating":"5", "tmdb_id":123, "": ""). */
    private fun JsonReader.lenientString(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    // Mesmo UA da lista M3U: alguns painéis filtram por User-Agent.
    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
}
