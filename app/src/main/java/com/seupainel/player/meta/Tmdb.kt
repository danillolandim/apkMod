package com.seupainel.player.meta

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente mínimo do TMDB (themoviedb.org) para buscar capa, nota e sinopse.
 * Precisa de uma API key (gratuita) configurada nas Configurações.
 *
 * Busca em pt-BR; se a sinopse vier vazia, tenta en-US (TMDB não faz fallback).
 */
object Tmdb {

    data class Meta(
        val title: String,
        val posterUrl: String?,
        val rating: Double,        // 0..10 (vote_average)
        val overview: String?,
        val year: String?,
    )

    private const val IMG = "https://image.tmdb.org/t/p/w500"

    /** Limpa o título da lista para casar no TMDB (tira SxxExx, qualidade, ano, tags). */
    fun cleanTitle(raw: String): String {
        var s = raw
        s = s.replace(Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b"""), " ")
        s = s.replace(Regex("""(?i)\bT\d{1,2}\s*E\d{1,3}\b"""), " ")
        s = s.replace(Regex("""(?i)\b(1080p|720p|480p|4k|uhd|fhd|hd|sd|dual|leg|legendado|dublado|dub|multi|h264|h265|hevc)\b"""), " ")
        s = s.replace(Regex("""\(?\b(19|20)\d{2}\b\)?"""), " ")   // ano
        s = s.replace(Regex("""[\[\](){}|]"""), " ")
        s = s.replace(Regex("""\s+-\s+.*$"""), "")                 // "Nome - episódio"
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return s
    }

    /** @param isSeries true busca em /tv, senão /movie. */
    fun fetch(apiKey: String, rawTitle: String, isSeries: Boolean): Meta? {
        if (apiKey.isBlank()) return null
        val query = cleanTitle(rawTitle).ifBlank { rawTitle }
        val kind = if (isSeries) "tv" else "movie"
        val first = search(apiKey, kind, query, "pt-BR")
            ?: search(apiKey, if (isSeries) "movie" else "tv", query, "pt-BR") // tenta o outro tipo
            ?: return null

        var overview = first.optString("overview", "")
        if (overview.isBlank()) {
            // tenta detalhes em en-US para sinopse
            val id = first.optInt("id", 0)
            if (id > 0) overview = detailsOverview(apiKey, kind, id)
        }
        val poster = first.optString("poster_path", "").takeIf { it.isNotBlank() }?.let { IMG + it }
        val name = first.optString(if (isSeries) "name" else "title", query)
        val date = first.optString(if (isSeries) "first_air_date" else "release_date", "")
        return Meta(
            title = name,
            posterUrl = poster,
            rating = first.optDouble("vote_average", 0.0),
            overview = overview.takeIf { it.isNotBlank() },
            year = date.take(4).takeIf { it.isNotBlank() },
        )
    }

    private fun search(apiKey: String, kind: String, query: String, lang: String): JSONObject? {
        val url = "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&language=$lang&include_adult=false&query=" +
            URLEncoder.encode(query, "UTF-8")
        val json = httpGet(url) ?: return null
        val results = JSONObject(json).optJSONArray("results") ?: return null
        return if (results.length() > 0) results.getJSONObject(0) else null
    }

    private fun detailsOverview(apiKey: String, kind: String, id: Int): String {
        val url = "https://api.themoviedb.org/3/$kind/$id?api_key=$apiKey&language=en-US"
        val json = httpGet(url) ?: return ""
        return JSONObject(json).optString("overview", "")
    }

    private fun httpGet(urlStr: String): String? = try {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 12_000; instanceFollowRedirects = true
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() }
        else null
    } catch (_: Exception) { null }
}
