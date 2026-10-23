package com.seupainel.player

import android.content.Context

/**
 * Preferências do app/player, editáveis na tela de Configurações e lidas pelo
 * player em runtime. Tudo persistido; sobrevive a reinstalações do mesmo app.
 */
class AppSettings(context: Context) {

    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Idioma de áudio preferido (BCP-47). Vazio = sem preferência. */
    var audioLanguage: String
        get() = p.getString("audio_lang", "pt") ?: "pt"
        set(v) = p.edit().putString("audio_lang", v.trim()).apply()

    /** Idioma de legenda preferido (BCP-47). */
    var subtitleLanguage: String
        get() = p.getString("sub_lang", "pt") ?: "pt"
        set(v) = p.edit().putString("sub_lang", v.trim()).apply()

    /** Tamanho da legenda em SP. */
    var subtitleSizeSp: Float
        get() = p.getFloat("sub_size", 20f)
        set(v) = p.edit().putFloat("sub_size", v).apply()

    /** Índice do modo de aspecto padrão (ver PlayerController.ASPECTS). */
    var aspectIndex: Int
        get() = p.getInt("aspect", 0)
        set(v) = p.edit().putInt("aspect", v).apply()

    /** Travar sempre na máxima qualidade (ignora ABR). */
    var forceMaxQuality: Boolean
        get() = p.getBoolean("force_max", false)
        set(v) = p.edit().putBoolean("force_max", v).apply()

    /** Qualidade da conexão -> estimativa inicial de banda (Mbps). */
    var connectionMbps: Int
        get() = p.getInt("conn_mbps", 10)
        set(v) = p.edit().putInt("conn_mbps", v).apply()

    /** Retomar de onde parou ("continuar assistindo"). */
    var resumeEnabled: Boolean
        get() = p.getBoolean("resume", true)
        set(v) = p.edit().putBoolean("resume", v).apply()

    /** Reconectar automaticamente em caso de falha. */
    var autoRetry: Boolean
        get() = p.getBoolean("auto_retry", true)
        set(v) = p.edit().putBoolean("auto_retry", v).apply()

    /** Verificar atualização do app ao abrir. */
    var autoUpdate: Boolean
        get() = p.getBoolean("auto_update", true)
        set(v) = p.edit().putBoolean("auto_update", v).apply()

    /** Papel de parede: chave de tema (ver WALLPAPERS) ou "custom". */
    var wallpaper: String
        get() = p.getString("wallpaper", "emerald") ?: "emerald"
        set(v) = p.edit().putString("wallpaper", v).apply()

    /** URL de imagem personalizada para o papel de parede (ex.: Unsplash). */
    var wallpaperUrl: String
        get() = p.getString("wallpaper_url", "") ?: ""
        set(v) = p.edit().putString("wallpaper_url", v.trim()).apply()

    /** Chave da API do TMDB (themoviedb.org) para capa/nota/sinopse. */
    var tmdbApiKey: String
        get() = p.getString("tmdb_key", "") ?: ""
        set(v) = p.edit().putString("tmdb_key", v.trim()).apply()

    companion object {
        /** Opções de "qualidade da conexão" exibidas (rótulo -> Mbps). */
        val CONNECTION_OPTIONS = listOf(
            "Automática (10 Mbps)" to 10,
            "Conexão lenta (3 Mbps)" to 3,
            "Boa (10 Mbps)" to 10,
            "Rápida / fibra (20 Mbps)" to 20,
            "Muito rápida (40 Mbps)" to 40,
            "Cabo de rede (80 Mbps)" to 80,
        )
        val SUBTITLE_SIZES = listOf("Pequena" to 16f, "Média" to 20f, "Grande" to 26f, "Enorme" to 34f)
        val LANG_OPTIONS = listOf(
            "Português" to "pt", "Inglês" to "en", "Espanhol" to "es",
            "Japonês" to "ja", "Original/qualquer" to "",
        )

        /** Papéis de parede embutidos (rótulo -> chave/drawable) + Personalizado. */
        val WALLPAPERS = listOf(
            "Emerald Bay (foto)" to "emerald",
            "Entardecer" to "dusk",
            "Noite" to "night",
            "Oceano" to "ocean",
            "Floresta" to "forest",
            "Imagem personalizada (URL)" to "custom",
        )
        fun wallpaperDrawable(key: String): String = when (key) {
            "emerald" -> "bg_emerald"   // foto embutida (CI); cai no vetor se faltar
            "night" -> "wallpaper_night"
            "ocean" -> "wallpaper_ocean"
            "forest" -> "wallpaper_forest"
            else -> "wallpaper"   // dusk (padrão)
        }

        /** User-Agents comuns aceitos por provedores IPTV (rótulo -> valor). */
        val UA_PRESETS = listOf(
            "— Personalizado / vazio —" to "",
            "VLC 3.0.20" to "VLC/3.0.20 LibVLC/3.0.20",
            "FFmpeg (Lavf)" to "Lavf/60.3.100",
            "IPTV Smarters" to "IPTVSmartersPlayer",
            "TiviMate" to "TiviMate/4.7.0 (Android 12)",
            "Kodi" to "Kodi/20.2 (Linux; Android 12) ExoPlayerLib/2.18",
            "OkHttp" to "okhttp/4.9.3",
            "Chrome (Android TV)" to "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
        )
    }
}
