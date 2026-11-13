package com.seupainel.player

/** Decisões da atualização sem Android (testadas em JVM). */
object UpdateLogic {

    /** Espera mínima entre duas verificações automáticas: voltar do instalador ou da voz não dispara outra. */
    const val AUTO_GAP_MS = 10 * 60 * 1000L

    /** O version.txt é só o número do build; qualquer outra coisa (página de erro, vazio) não é versão. */
    fun parseVersion(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * Avisa quando há versão mais nova. A verificação automática respeita a versão que o usuário pulou (até sair
     * uma mais nova que ela); a manual, pelo botão das Configurações, sempre avisa.
     */
    fun shouldPrompt(remote: Int, local: Int, skipped: Int, manual: Boolean): Boolean =
        remote > local && (manual || remote > skipped)

    /** [last] = 0 quando ainda não verificou neste processo. */
    fun dueForAutoCheck(now: Long, last: Long, gapMs: Long = AUTO_GAP_MS): Boolean =
        last <= 0L || now - last >= gapMs || now < last

    /** Só a seção "Esta versão" do changelog publicado. */
    fun whatsNew(changelog: String?): String? = changelog
        ?.substringAfter("## Esta versão", "")
        ?.substringBefore("## ", "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /** 0..100, ou null quando o servidor não informou o tamanho. */
    fun percent(done: Long, total: Long): Int? =
        if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else null

    /** Download inteiro: não vazio e, se o tamanho foi informado, exatamente ele. */
    fun complete(written: Long, expected: Long): Boolean = written > 0 && (expected <= 0 || written == expected)
}
