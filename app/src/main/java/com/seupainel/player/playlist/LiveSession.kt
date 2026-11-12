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
)

/**
 * Categoria da lista de canais sobreposta ao vídeo. [indices] aponta para a lista "Todos" (a da sessão),
 * então o número do canal (índice + 1) é o mesmo em qualquer categoria e o do zapping.
 */
data class LiveGroup(val name: String, val indices: List<Int>) {

    /** Posição de um canal dentro desta categoria, ou -1 se ele não pertence a ela. */
    fun positionOf(liveIndex: Int): Int = indices.indexOf(liveIndex)

    companion object {
        const val ALL = "Todos"

        /**
         * "Todos" primeiro, depois uma categoria por valor de [LiveChannel.group]. [order] é a ordem que a tela
         * de canais usa no menu; o que não estiver nela vem em seguida, pela primeira aparição. Canal sem grupo
         * aparece só em "Todos".
         */
        fun groupsOf(channels: List<LiveChannel>, order: List<String> = emptyList()): List<LiveGroup> {
            val byName = LinkedHashMap<String, MutableList<Int>>()
            channels.forEachIndexed { i, ch ->
                val name = ch.group.trim()
                if (name.isNotEmpty() && !name.equals(ALL, ignoreCase = true)) {
                    byName.getOrPut(name) { ArrayList() }.add(i)
                }
            }
            val preferred = order.map { it.trim() }.filter { it in byName }.distinct()
            val names = preferred + byName.keys.filter { it !in preferred }
            return buildList {
                add(LiveGroup(ALL, channels.indices.toList()))
                names.forEach { add(LiveGroup(it, byName.getValue(it))) }
            }
        }
    }
}

/**
 * Lista de canais ao vivo da sessão, só em memória: a tela de canais preenche antes de abrir o player.
 * Se o processo for recriado ela volta vazia e o player cai no modo de canal único (extras do Intent).
 */
object LiveSession {
    var channels: List<LiveChannel> = emptyList()
        private set
    var groups: List<LiveGroup> = LiveGroup.groupsOf(emptyList())
        private set
    var startIndex: Int = 0
        private set

    fun set(list: List<LiveChannel>, index: Int, categoryOrder: List<String> = emptyList()) {
        channels = list
        groups = LiveGroup.groupsOf(list, categoryOrder)
        startIndex = index
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
