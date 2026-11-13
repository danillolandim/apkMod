package com.seupainel.player

/**
 * Versão LEGENDADA escolhida pelo usuário: o player prefere áudio em português por padrão, então num arquivo com
 * dois áudios (português + original) a versão "legendada" tocaria dublada. Aqui se decide a troca para o original.
 */
object SubtitledAudio {

    fun isPortuguese(language: String?): Boolean {
        val l = language?.trim()?.lowercase().orEmpty()
        return l == "pt" || l.startsWith("pt-") || l.startsWith("pt_") || l == "por" || l == "pob"
    }

    /**
     * Índice da faixa de áudio para trocar, ou null para deixar como está: só troca quando a faixa em uso é em
     * português e existe outra que não é (com idioma declarado de preferência; sem idioma serve se for a única opção).
     */
    fun originalTrack(languages: List<String?>, selected: Int): Int? {
        if (selected !in languages.indices || !isPortuguese(languages[selected])) return null
        val others = languages.indices.filter { !isPortuguese(languages[it]) }
        return others.firstOrNull { !languages[it].isNullOrBlank() && languages[it] != "und" } ?: others.firstOrNull()
    }

    /** Legenda em português para ligar quando nenhuma está ligada; null se não há ou já há uma ligada. */
    fun portugueseSubtitle(languages: List<String?>, selected: Int): Int? =
        if (selected in languages.indices) null else languages.indices.firstOrNull { isPortuguese(languages[it]) }
}
