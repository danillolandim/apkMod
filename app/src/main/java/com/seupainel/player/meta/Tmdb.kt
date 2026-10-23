package com.seupainel.player.meta

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente mínimo do TMDB (themoviedb.org) para capa, nota e sinopse.
 *
 * Aceita tanto a "API Key (v3 auth)" (código curto, via ?api_key=) quanto o
 * "API Read Access Token (v4)" (JWT longo começando com eyJ..., via Bearer).
 * Busca em /search/multi (filmes + séries), pt-BR com fallback en-US.
 */
object Tmdb {

    data class Meta(
        val title: String,
        val posterUrl: String?,
        val rating: Double,
        val overview: String?,
        val year: String?,
    )

    sealed class Res {
        data class Ok(val meta: Meta) : Res()
        data object NotFound : Res()
        data object InvalidKey : Res()
        data class Err(val msg: String) : Res()
    }

    /** Dados rápidos para o selo no cartaz (só /search/multi, sem detalhes). */
    data class Quick(val rating: Double, val posterUrl: String?)

    /** Dados para indexar e filtrar VOD: gênero, ano, nota e cartaz, num só request. */
    data class Cat(
        val genreIds: List<Int>,
        val year: Int?,
        val rating: Double,
        val posterUrl: String?,
    )

    /** Uma única chamada /search/multi que devolve gênero + ano + nota + cartaz. */
    fun categorize(apiKey: String, rawTitle: String): Cat? {
        if (apiKey.isBlank()) return null
        val q = cleanTitle(rawTitle).ifBlank { rawTitle }
        val (code, json) = req(apiKey, "/search/multi", mapOf(
            "query" to q, "language" to "pt-BR", "include_adult" to "false",
        ))
        if (code !in 200..299 || json == null) return null
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val mt = o.optString("media_type")
            if (mt == "movie" || mt == "tv") {
                val arr = o.optJSONArray("genre_ids")
                val ids = if (arr != null) (0 until arr.length()).map { arr.optInt(it) } else emptyList()
                val date = o.optString(if (mt == "tv") "first_air_date" else "release_date", "")
                val poster = o.optString("poster_path", "").takeIf { it.isNotBlank() }?.let { IMG + it }
                return Cat(ids, date.take(4).toIntOrNull(), o.optDouble("vote_average", 0.0), poster)
            }
        }
        return null
    }

    fun quick(apiKey: String, rawTitle: String): Quick? {
        if (apiKey.isBlank()) return null
        val q = cleanTitle(rawTitle).ifBlank { rawTitle }
        val (code, json) = req(apiKey, "/search/multi", mapOf(
            "query" to q, "language" to "pt-BR", "include_adult" to "false",
        ))
        if (code !in 200..299 || json == null) return null
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val mt = o.optString("media_type")
            if (mt == "movie" || mt == "tv") {
                val poster = o.optString("poster_path", "").takeIf { it.isNotBlank() }?.let { IMG + it }
                return Quick(o.optDouble("vote_average", 0.0), poster)
            }
        }
        return null
    }

    private const val IMG = "https://image.tmdb.org/t/p/w500"

    fun cleanTitle(raw: String): String {
        var s = raw
        s = s.replace(Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b"""), " ")
        s = s.replace(Regex("""(?i)\bT\d{1,2}\s*E\d{1,3}\b"""), " ")
        s = s.replace(Regex("""(?i)\b(1080p|720p|480p|4k|uhd|fhd|hd|sd|dual|leg|legendado|dublado|dub|multi|h264|h265|hevc)\b"""), " ")
        s = s.replace(Regex("""\(?\b(19|20)\d{2}\b\)?"""), " ")
        s = s.replace(Regex("""[\[\](){}|]"""), " ")
        s = s.replace(Regex("""\s+-\s+.*$"""), "")
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return s
    }

    fun fetch(apiKey: String, rawTitle: String, isSeries: Boolean): Res {
        if (apiKey.isBlank()) return Res.Err("sem chave")
        val query = cleanTitle(rawTitle).ifBlank { rawTitle }

        // tenta título limpo; se não achar, tenta o título cru
        for (q in listOf(query, rawTitle).distinct()) {
            when (val r = searchMulti(apiKey, q)) {
                is Res.InvalidKey -> return r
                is Res.Err -> return r
                is Res.Ok -> return r
                is Res.NotFound -> { /* tenta próximo */ }
            }
        }
        return Res.NotFound
    }

    private fun searchMulti(apiKey: String, query: String): Res {
        val (code, json) = req(apiKey, "/search/multi", mapOf(
            "query" to query, "language" to "pt-BR", "include_adult" to "false",
        ))
        if (code == 401) return Res.InvalidKey
        if (code !in 200..299 || json == null) return Res.Err("HTTP $code")
        val results = json.optJSONArray("results") ?: return Res.NotFound
        // primeiro resultado que seja filme ou série
        var chosen: JSONObject? = null
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val mt = o.optString("media_type")
            if (mt == "movie" || mt == "tv") { chosen = o; break }
        }
        val first = chosen ?: return Res.NotFound
        val isTv = first.optString("media_type") == "tv"

        var overview = first.optString("overview", "")
        if (overview.isBlank()) {
            val id = first.optInt("id", 0)
            if (id > 0) overview = detailsOverview(apiKey, if (isTv) "tv" else "movie", id)
        }
        val poster = first.optString("poster_path", "").takeIf { it.isNotBlank() }?.let { IMG + it }
        val name = first.optString(if (isTv) "name" else "title", query)
        val date = first.optString(if (isTv) "first_air_date" else "release_date", "")
        return Res.Ok(
            Meta(
                title = name,
                posterUrl = poster,
                rating = first.optDouble("vote_average", 0.0),
                overview = overview.takeIf { it.isNotBlank() },
                year = date.take(4).takeIf { it.isNotBlank() },
            )
        )
    }

    private fun detailsOverview(apiKey: String, kind: String, id: Int): String {
        val (code, json) = req(apiKey, "/$kind/$id", mapOf("language" to "en-US"))
        return if (code in 200..299 && json != null) json.optString("overview", "") else ""
    }

    /** Monta a requisição com v3 (api_key=) ou v4 (Bearer), devolve (código, json). */
    private fun req(apiKey: String, path: String, params: Map<String, String>): Pair<Int, JSONObject?> {
        val isV4 = apiKey.startsWith("eyJ")
        val qp = StringBuilder()
        params.forEach { (k, v) -> qp.append(if (qp.isEmpty()) "?" else "&").append(k).append("=").append(URLEncoder.encode(v, "UTF-8")) }
        if (!isV4) qp.append(if (qp.isEmpty()) "?" else "&").append("api_key=").append(apiKey.trim())
        return try {
            val c = (URL("https://api.themoviedb.org/3$path$qp").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 12_000; instanceFollowRedirects = true
                if (isV4) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                setRequestProperty("Accept", "application/json")
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            code to body?.let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: Exception) {
            -1 to null
        }
    }
}
