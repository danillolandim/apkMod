package com.seupainel.player

import androidx.media3.common.MimeTypes
import org.json.JSONArray

/**
 * Modelos de conteúdo espelhados do APK original (io.tvs.android.one 11.6.0):
 *
 *   Source   { _id, address, short_title, List<Subtitles> subtitles, ... }
 *   Subtitles{ address (=URL da legenda), name }
 *   ChannelBean.SourcesBean.SubtitlesBean { code (idioma), type (srt/vtt), url }
 *
 * `address` é o que vai para TVCore.start(). `subtitles` são as legendas
 * externas que o SEU painel já entrega por URL — aqui elas de fato entram
 * no player (o app original só ligava faixa interna).
 */
data class PanelSubtitle(
    val url: String,
    /** idioma BCP-47, ex.: "pt-BR", "en" (campo `code` do painel) */
    val language: String = "pt-BR",
    /** "srt" | "vtt" | "ass"/"ssa" (campo `type` do painel) */
    val type: String = "srt",
    /** rótulo exibido (campo `name`); se vazio, usa o idioma */
    val name: String = "",
)

data class PanelSource(
    /** endereço P2P passado a TVCore.start() */
    val address: String,
    val title: String = "",
    val subtitles: List<PanelSubtitle> = emptyList(),
    /** posição para retomar (ms) */
    val startPositionMs: Long = 0,
    /** endereço alternativo (campo address_backup do painel), usado em falha */
    val addressBackup: String = "",
    /** true para conteúdo ao vivo (buffer de baixa latência) */
    val isLive: Boolean = false,
)

fun PanelSubtitle.toExternal(makeDefault: Boolean): PlayerController.ExternalSubtitle =
    PlayerController.ExternalSubtitle(
        url = url,
        label = name.ifBlank { language },
        language = language,
        mime = mimeOf(type),
        makeDefault = makeDefault,
    )

/** A primeira legenda PT vira a default; senão a primeira da lista. */
fun List<PanelSubtitle>.toExternalList(): List<PlayerController.ExternalSubtitle> {
    val defaultIdx = indexOfFirst { it.language.startsWith("pt", ignoreCase = true) }
        .let { if (it >= 0) it else if (isNotEmpty()) 0 else -1 }
    return mapIndexed { i, s -> s.toExternal(makeDefault = i == defaultIdx) }
}

private fun mimeOf(type: String): String = when (type.lowercase().removePrefix(".")) {
    "vtt", "webvtt"      -> MimeTypes.TEXT_VTT
    "ass", "ssa"         -> MimeTypes.TEXT_SSA
    "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
    else                  -> MimeTypes.APPLICATION_SUBRIP // srt
}

/** Serializa/parseia a lista de legendas para passar via Intent. */
object SubtitleJson {
    fun encode(list: List<PanelSubtitle>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                org.json.JSONObject()
                    .put("url", s.url)
                    .put("language", s.language)
                    .put("type", s.type)
                    .put("name", s.name)
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<PanelSubtitle> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PanelSubtitle(
                    url = o.optString("url"),
                    language = o.optString("language", "pt-BR"),
                    type = o.optString("type", "srt"),
                    name = o.optString("name", ""),
                )
            }.filter { it.url.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
