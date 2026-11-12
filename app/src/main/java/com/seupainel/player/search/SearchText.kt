package com.seupainel.player.search

import java.text.Normalizer

/** Texto de busca: sem acento, sem maiúsculas e sem pontuação, para o título e a consulta se encontrarem. */
object SearchText {

    // Apóstrofo e ponto colam as letras em vez de separar: "Grey's" vira "greys" e "S.W.A.T." vira "swat".
    private const val GLUED = "'’‘`´."

    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var pendingSpace = false
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                c in GLUED -> {}
                Character.isLetterOrDigit(c) -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(c.lowercaseChar())
                }
                else -> pendingSpace = true
            }
        }
        return out.toString()
    }

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }
}

/** Um título pesquisável. [rating] e [added] só servem de desempate. */
class SearchDoc(val title: String, val rating: Double = 0.0, val added: Long = 0L)

/**
 * Índice de títulos já normalizados, montado uma vez e reaproveitado a cada tecla. A busca percorre todos os
 * títulos (dezenas de milhares) mas só guarda os [limit] melhores, então o custo é uma passada com comparações.
 */
class SearchIndex(private val docs: List<SearchDoc>) {

    /** [total] = todos os que casaram; [top] = índices em [docs] dos melhores, já na ordem de exibição. */
    class Result(val total: Int, val top: List<Int>)

    private val norm: Array<String> = Array(docs.size) { SearchText.normalize(docs[it].title) }

    val size: Int get() = docs.size

    /** null = cancelada no meio (a consulta mudou); [cancelled] é conferido de tempos em tempos. */
    fun search(query: String, limit: Int, cancelled: () -> Boolean = { false }): Result? {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty() || limit <= 0) return Result(0, emptyList())
        val normQuery = tokens.joinToString(" ")

        val ranks = IntArray(docs.size) { -1 }
        // Fila com o pior dos guardados na frente: ao passar de [limit], sai o pior.
        val best = java.util.PriorityQueue<Int>(limit + 1) { a, b -> compare(b, a, ranks) }
        var total = 0
        for (i in norm.indices) {
            if (i and CANCEL_CHECK_MASK == 0 && cancelled()) return null
            val r = rank(norm[i], normQuery, tokens)
            if (r < 0) continue
            ranks[i] = r
            total++
            best.add(i)
            if (best.size > limit) best.poll()
        }
        return Result(total, best.sortedWith { a, b -> compare(a, b, ranks) })
    }

    // Nível, depois nota (maior primeiro), depois mais recente, depois ordem alfabética (decisão estável).
    private fun compare(a: Int, b: Int, ranks: IntArray): Int {
        if (ranks[a] != ranks[b]) return ranks[a].compareTo(ranks[b])
        val ra = docs[a].rating.takeIf { !it.isNaN() } ?: 0.0
        val rb = docs[b].rating.takeIf { !it.isNaN() } ?: 0.0
        if (ra != rb) return rb.compareTo(ra)
        if (docs[a].added != docs[b].added) return docs[b].added.compareTo(docs[a].added)
        val byTitle = norm[a].compareTo(norm[b])
        return if (byTitle != 0) byTitle else a.compareTo(b)
    }

    companion object {
        private const val CANCEL_CHECK_MASK = 0x7FF

        /**
         * -1 = não casa (falta alguma palavra); 0 = o título começa com o texto; 1 = cada palavra digitada começa
         * uma palavra do título; 2 = só contém.
         */
        fun rank(normTitle: String, normQuery: String, tokens: List<String>): Int {
            for (t in tokens) if (!normTitle.contains(t)) return -1
            if (normTitle.startsWith(normQuery)) return 0
            val words = normTitle.split(' ')
            return if (tokens.all { t -> words.any { it.startsWith(t) } }) 1 else 2
        }
    }
}
