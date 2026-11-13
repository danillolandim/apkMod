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
