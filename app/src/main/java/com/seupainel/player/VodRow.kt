package com.seupainel.player

/**
 * Lógica pura (sem Android) da fileira de botões do filme/série na TV (▶/❚❚, Áudio e legendas, Próximo episódio,
 * Mais opções). O foco é "virtual": a Activity guarda só um índice ([LINE] = a linha de progresso está no comando,
 * 0..n-1 = botão escolhido) e este objeto diz quais botões existem e para onde o índice anda.
 */
object VodRow {

    enum class Button { PLAY, AUDIO, NEXT, MORE }

    /** Índice de "modo linha": nenhum botão escolhido. */
    const val LINE = -1

    /** Botões na tela, da esquerda para a direita. O "Próximo episódio" só existe quando há próximo item na fila. */
    fun buttons(hasNext: Boolean): List<Button> =
        if (hasNext) listOf(Button.PLAY, Button.AUDIO, Button.NEXT, Button.MORE)
        else listOf(Button.PLAY, Button.AUDIO, Button.MORE)

    /** ← → : anda um botão ([direction]: só o sinal vale) e para nas pontas, sem dar a volta. No modo linha não faz nada. */
    fun move(idx: Int, direction: Int, count: Int): Int {
        if (idx < 0 || count <= 0) return LINE
        return (idx + direction.coerceIn(-1, 1)).coerceIn(0, count - 1)
    }

    /**
     * A lista de botões mudou (o "Próximo episódio" apareceu ou sumiu): mantém o mesmo botão escolhido. Se o escolhido
     * foi o que sumiu, fica no índice mais próximo.
     */
    fun remap(old: List<Button>, idx: Int, new: List<Button>): Int {
        if (idx < 0 || new.isEmpty()) return LINE
        val sameButton = old.getOrNull(idx)?.let(new::indexOf) ?: -1
        return if (sameButton >= 0) sameButton else idx.coerceIn(0, new.size - 1)
    }
}
