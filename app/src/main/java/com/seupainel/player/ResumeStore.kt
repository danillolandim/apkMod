package com.seupainel.player

import android.content.Context

/**
 * "Continuar assistindo": guarda a última posição por conteúdo.
 * Simples e sem dependências (SharedPreferences). Chave = address.
 */
class ResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        // Não guarda se quase no fim (considera "assistido").
        if (durationMs > 0 && positionMs > durationMs - 15_000) {
            prefs.edit().remove(key).apply()
            return
        }
        if (positionMs > 10_000) prefs.edit().putLong(key, positionMs).apply()
    }

    fun get(key: String): Long = prefs.getLong(key, 0L)

    fun clear(key: String) = prefs.edit().remove(key).apply()
}
