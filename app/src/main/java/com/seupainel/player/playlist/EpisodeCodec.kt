package com.seupainel.player.playlist

import org.json.JSONArray
import org.json.JSONObject

/**
 * Episódios entre telas, em JSON dentro do Intent. O formato antigo (s, e, n, u, l) continua lendo; os dados extras
 * (t, p, d, i) só existem para séries da API. A sinopse é cortada: o Intent tem um limite de tamanho e uma série longa
 * leva centenas de episódios.
 */
object EpisodeCodec {

    private const val MAX_PLOT = 280

    fun encode(eps: List<SeriesGrouper.Ep>): String {
        val arr = JSONArray()
        eps.forEach { e ->
            val o = JSONObject()
                .put("s", e.season).put("e", e.episode)
                .put("n", e.channel.name).put("u", e.channel.url).put("l", e.leg)
            e.info?.let { i ->
                if (i.title.isNotEmpty()) o.put("t", i.title)
                if (i.plot.isNotEmpty()) o.put("p", clip(i.plot))
                if (i.durationSec > 0) o.put("d", i.durationSec)
                if (i.image.isNotEmpty()) o.put("i", i.image)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String?): List<SeriesGrouper.Ep> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val info = SeriesGrouper.EpisodeInfo(o.optString("t"), o.optString("p"), o.optInt("d", 0), o.optString("i"))
                    .takeIf { i -> i.title.isNotEmpty() || i.plot.isNotEmpty() || i.durationSec > 0 || i.image.isNotEmpty() }
                SeriesGrouper.Ep(
                    o.optInt("s", 1), o.optInt("e", 1),
                    M3uParser.Channel(name = o.optString("n"), url = o.optString("u"), group = "", duration = 0),
                    o.optBoolean("l", false), info,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clip(text: String) = if (text.length <= MAX_PLOT) text else text.take(MAX_PLOT - 1).trimEnd() + "…"
}
