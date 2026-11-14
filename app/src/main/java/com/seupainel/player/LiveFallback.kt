package com.seupainel.player

/**
 * Canal ao vivo com mais de uma qualidade (da melhor para a pior): quando a qualidade em uso não abre (erro do
 * servidor ou demora), o player desce para a seguinte sozinho. Aqui ficam as decisões, sem Android.
 */
object LiveFallback {

    /** Tempo para o canal começar a tocar antes de tentar outra qualidade. */
    const val START_TIMEOUT_MS = 10_000L

    /** Por quanto tempo vale lembrar a qualidade que funcionou num canal (a fonte boa pode voltar). */
    const val MEMO_TTL_MS = 30 * 60 * 1000L

    /**
     * Próxima qualidade a tentar: a seguinte mais baixa que ainda não falhou; sem nenhuma abaixo, uma acima que
     * ainda não foi tentada. null = todas já falharam.
     */
    fun next(count: Int, current: Int, failed: Set<Int>): Int? {
        val open = (0 until count).filter { it != current && it !in failed }
        return open.firstOrNull { it > current } ?: open.firstOrNull()
    }

    /** Tempo com a imagem parada (canal que já estava tocando) até o player agir. */
    const val STALL_TIMEOUT_MS = 8_000L

    /** Tocando sem travar por este tempo, a próxima trava volta a ser tratada como a primeira. */
    const val STALL_CALM_MS = 30_000L

    /** Reconexões seguidas no mesmo endereço antes de desistir (canal sem outra qualidade). */
    const val STALL_MAX_RECONNECTS = 3

    enum class StallAction { RECONNECT, NEXT_QUALITY, GIVE_UP }

    /**
     * Vigia o canal que já estava tocando: se a posição do vídeo para de andar, primeiro reconecta no mesmo
     * endereço (o que a pessoa faria escolhendo o canal de novo); travou outra vez logo em seguida, desce de
     * qualidade; sem outra qualidade, reconecta mais algumas vezes e então desiste.
     */
    class StallWatch(
        private val timeoutMs: Long = STALL_TIMEOUT_MS,
        private val calmMs: Long = STALL_CALM_MS,
        private val maxReconnects: Int = STALL_MAX_RECONNECTS,
    ) {
        private var lastPos = Long.MIN_VALUE
        private var since = 0L
        private var lastStallAt = 0L
        private var stalls = 0

        /** Canal novo: esquece tudo. */
        fun reset(now: Long) { lastPos = Long.MIN_VALUE; since = now; stalls = 0 }

        /** Pausado ou ainda abrindo: o tempo parado não conta. */
        fun touch(now: Long) { since = now }

        /** Chamado a cada segundo com a posição do vídeo. null = está tudo bem (ou ainda dentro da espera). */
        fun tick(position: Long, now: Long, hasOtherQuality: Boolean): StallAction? {
            if (position != lastPos) {
                lastPos = position
                since = now
                if (stalls > 0 && now - lastStallAt >= calmMs) stalls = 0
                return null
            }
            if (now - since < timeoutMs) return null
            since = now
            lastStallAt = now
            stalls++
            return when {
                stalls == 1 -> StallAction.RECONNECT
                hasOtherQuality -> StallAction.NEXT_QUALITY
                stalls <= maxReconnects -> StallAction.RECONNECT
                else -> StallAction.GIVE_UP
            }
        }
    }

    /** Qualidade que funcionou por canal, só na memória do app. */
    class Memo(private val ttlMs: Long = MEMO_TTL_MS) {
        private class Entry(val index: Int, val at: Long)

        private val map = HashMap<String, Entry>()

        @Synchronized fun put(key: String, index: Int, now: Long) {
            if (key.isNotBlank()) map[key] = Entry(index, now)
        }

        /** null quando não há lembrança, ela venceu ou o canal já não tem aquela qualidade. */
        @Synchronized fun get(key: String, count: Int, now: Long): Int? {
            val e = map[key] ?: return null
            if (now - e.at > ttlMs || now < e.at || e.index !in 0 until count) { map.remove(key); return null }
            return e.index
        }

        @Synchronized fun forget(key: String) { map.remove(key) }
    }
}
