package com.seupainel.player.search

import com.seupainel.player.playlist.Dub
import com.seupainel.player.playlist.M3uParser

/** Dublado e legendado do mesmo filme como um item só, como na lista de Filmes. O representante é o dublado. */
class DubLegGroup(val rep: M3uParser.Channel, val dub: M3uParser.Channel?, val leg: M3uParser.Channel?) {

    val title: String get() = Dub.base(rep.name)

    companion object {
        /** Mantém a ordem em que cada título aparece primeiro. */
        fun group(list: List<M3uParser.Channel>): List<DubLegGroup> {
            val byBase = LinkedHashMap<String, MutableList<M3uParser.Channel>>()
            for (ch in list) byBase.getOrPut(SearchText.normalize(Dub.base(ch.name))) { ArrayList() }.add(ch)
            return byBase.values.map { variants ->
                val dub = variants.firstOrNull { !Dub.isLeg(it.name) }
                val leg = variants.firstOrNull { Dub.isLeg(it.name) }
                DubLegGroup(dub ?: leg ?: variants.first(), dub, leg)
            }
        }
    }
}
