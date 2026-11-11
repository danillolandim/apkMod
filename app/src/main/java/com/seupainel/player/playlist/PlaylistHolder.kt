package com.seupainel.player.playlist

/**
 * Guarda a lista já interpretada em memória, durante a vida do processo.
 * Preenchido no Splash (ao abrir o app) e reutilizado pela tela de canais,
 * para que abrir qualquer seção seja instantâneo (sem reparsear 12 MB).
 */
object PlaylistHolder {
    @Volatile var channels: List<M3uParser.Channel>? = null

    /** A pré-carga da abertura terminou (lista M3U e/ou catálogos da API já em memória). */
    @Volatile var preloadDone = false

    fun clear() { channels = null }
}
