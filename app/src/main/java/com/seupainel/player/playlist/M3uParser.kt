package com.seupainel.player.playlist

/**
 * Parser genérico de listas M3U / M3U8 (formato #EXTINF), como VLC e Kodi.
 * Neutro quanto à fonte: lê qualquer lista válida que o usuário fornecer.
 *
 * Reconhece:
 *   #EXTINF:-1 tvg-id="x" tvg-logo="y" group-title="Esportes",Nome do canal
 *   #EXTGRP:Categoria
 *   #EXTVLCOPT:http-user-agent=... / #EXTVLCOPT:http-referrer=...
 *   <url>
 *
 * Distingue uma LISTA de canais de um STREAM HLS único (que também usa a
 * extensão .m3u8): se houver tags #EXT-X-*, é um stream, não uma lista.
 */
object M3uParser {

    data class Channel(
        val name: String,
        val url: String,
        val group: String,
        val logo: String? = null,
        val tvgId: String? = null,
        /** -1 = ao vivo; > 0 = VOD (duração em segundos) */
        val duration: Int = -1,
        val userAgent: String? = null,
        val referer: String? = null,
    ) {
        val isLive get() = duration < 0
    }

    sealed class Result {
        /** O texto é um stream HLS (master/media playlist), não uma lista. */
        data object HlsStream : Result()
        data class Playlist(val channels: List<Channel>) : Result()
        data object Empty : Result()
    }

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val hlsTags = listOf(
        "#EXT-X-STREAM-INF", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-VERSION", "#EXT-X-MEDIA:", "#EXT-X-ENDLIST", "#EXT-X-PLAYLIST-TYPE",
    )

    fun parse(text: String): Result {
        if (hlsTags.any { text.contains(it) }) return Result.HlsStream

        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo: String? = null
        var tvgId: String? = null
        var duration = -1
        var ua: String? = null
        var referer: String? = null
        var pendingExtGrp: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val body = line.substring(8)
                    duration = body.takeWhile { it != ' ' && it != ',' }.toIntOrNull() ?: -1
                    val attrs = attrRegex.findAll(body)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    group = attrs["group-title"] ?: pendingExtGrp ?: ""
                    logo = attrs["tvg-logo"]
                    tvgId = attrs["tvg-id"]
                    val lastQuote = body.lastIndexOf('"')
                    val comma = body.indexOf(',', startIndex = if (lastQuote >= 0) lastQuote else 0)
                    name = (if (comma >= 0) body.substring(comma + 1) else "").trim()
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Sem nome"
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingExtGrp = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    when {
                        opt.startsWith("http-user-agent=", true) -> ua = opt.substringAfter('=').trim()
                        opt.startsWith("http-referrer=", true) -> referer = opt.substringAfter('=').trim()
                        opt.startsWith("http-referer=", true) -> referer = opt.substringAfter('=').trim()
                    }
                }
                line.startsWith("#") -> { /* outras tags: ignora */ }
                else -> {
                    val lower = line.lowercase()
                    if (lower.startsWith("http") || lower.startsWith("rtsp") || lower.startsWith("rtmp") || lower.startsWith("udp")) {
                        out.add(
                            Channel(
                                name = name.ifBlank { line.substringAfterLast('/') },
                                url = line,
                                group = group.ifBlank { "Sem categoria" },
                                logo = logo, tvgId = tvgId, duration = duration,
                                userAgent = ua, referer = referer,
                            )
                        )
                    }
                    name = ""; group = ""; logo = null; tvgId = null
                    duration = -1; ua = null; referer = null
                }
            }
        }
        return if (out.isEmpty()) Result.Empty else Result.Playlist(out)
    }
}
