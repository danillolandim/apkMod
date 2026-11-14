package com.seupainel.player.playlist

/**
 * Canal ao vivo como o player precisa para zapear. [variantUrls] fica vazio quando o canal tem uma
 * qualidade só (mesma convenção dos extras do Intent); com várias, a melhor vem primeiro e [url] é ela.
 */
data class LiveChannel(
    val name: String,
    val url: String,
    val logo: String,
    val tvgId: String,
    val apiId: Long,
    val group: String,
    val userAgent: String?,
    val referer: String?,
    val variantUrls: List<String>,
    val variantLabels: List<String>,
) {
    /** Todas as URLs que tocam este canal (a melhor primeiro). */
    val allUrls: List<String> get() = variantUrls.ifEmpty { listOf(url) }
}

/**
 * Categoria da lista de canais sobreposta ao vídeo. [indices] aponta para a lista "Todos" (a da sessão),
 * então o número do canal (índice + 1) é o mesmo em qualquer categoria e o do zapping.
 */
data class LiveGroup(val name: String, val indices: List<Int>) {

    /** Posição de um canal dentro desta categoria, ou -1 se ele não pertence a ela. */
    fun positionOf(liveIndex: Int): Int = indices.indexOf(liveIndex)

    /**
     * Vizinho de [liveIndex] dentro desta categoria ([dir] > 0 o próximo, < 0 o anterior), dando a volta nas
     * pontas. null se o canal não pertence a ela: aí o zapping não tem como se orientar por esta categoria.
     */
    fun step(liveIndex: Int, dir: Int): Int? {
        val pos = positionOf(liveIndex)
        if (pos < 0) return null
        val n = indices.size
        return indices[((pos + (if (dir > 0) 1 else -1)) % n + n) % n]
    }

    companion object {
        const val ALL = "Todos"
        const val FAVORITES = "Favoritos"
    }
}

/**
 * Seção de TV ao vivo inteira, só em memória: a tela de canais e a Home preenchem antes de abrir o player.
 * Se o processo for recriado ela volta vazia e o player cai no modo de canal único (extras do Intent).
 */
object LiveSession {
    var channels: List<LiveChannel> = emptyList()
        private set
    var groups: List<LiveGroup> = listOf(LiveGroup(LiveGroup.ALL, emptyList()))
        private set
    var startIndex: Int = 0
        private set

    /** Nome da categoria por onde a pessoa chegou ao canal ("" = não se sabe): o zapping fica dentro dela. */
    var startGroup: String = ""
        private set

    /** [groups] vem do [LiveCatalog]; sem ele a sessão tem só "Todos". */
    fun set(
        list: List<LiveChannel>, index: Int,
        groups: List<LiveGroup> = listOf(LiveGroup(LiveGroup.ALL, list.indices.toList())),
        startGroup: String = "",
    ) {
        channels = list
        this.groups = groups
        startIndex = index
        this.startGroup = startGroup
    }

    fun clear() = set(emptyList(), 0)

    /**
     * Índice do canal que o player deve abrir, ou null se a sessão não corresponde à URL recebida
     * (lista velha de outra navegação, ou aberto por outra tela): aí o player toca só aquele canal.
     */
    fun indexOf(url: String): Int? {
        val ch = channels.getOrNull(startIndex) ?: return null
        return startIndex.takeIf { ch.url == url || url in ch.variantUrls }
    }
}

/**
 * Lógica pura do zapping (sem Android, testável em JVM). Número do canal = índice + 1.
 *
 * [current] é o canal selecionado (o que a faixa mostra) e muda a cada toque; o canal de fato sintonizado só
 * muda em [commit], chamado quando o player começa a tocá-lo. Assim, segurar CH+ por 20 canais não faz de um
 * deles o "canal anterior": [last] volta ao que o usuário estava assistindo.
 */
class LiveZapper(val size: Int, start: Int = 0) {

    var current: Int = if (size > 0) start.coerceIn(0, size - 1) else -1
        private set

    /** Canal que de fato está tocando (muda só em [commit]). */
    var tuned: Int = current
        private set

    private var previous = -1

    fun next(): Int? = move { (current + 1) % size }

    fun prev(): Int? = move { (current - 1 + size) % size }

    fun jumpTo(index: Int): Int? = if (index in 0 until size) index.also { current = it } else null

    /** Índice do canal de número [number], sem mudar o canal atual; null se não existir. */
    fun byNumber(number: Int): Int? = (number - 1).takeIf { it in 0 until size }

    /** Volta ao canal sintonizado antes do atual; null se ainda não há histórico. */
    fun last(): Int? {
        commit()
        val p = previous
        if (p < 0 || p == current) return null
        current = p
        return p
    }

    /** Registra que o canal selecionado passou a ser o sintonizado. */
    fun commit() {
        if (current < 0 || current == tuned) return
        previous = tuned
        tuned = current
    }

    /** O canal selecionado já é o que está tocando (não precisa reconectar). */
    val isTuned: Boolean get() = current == tuned

    private inline fun move(target: () -> Int): Int? {
        if (size == 0) return null
        current = target()
        return current
    }
}
