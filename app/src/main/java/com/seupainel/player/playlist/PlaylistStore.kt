package com.seupainel.player.playlist

import android.content.Context
import java.io.File

/**
 * Preferências da lista + cache em disco.
 *
 *  - playlistUrl : URL da lista M3U.
 *  - configUrl   : (opcional) URL de um arquivo texto na nuvem cujo conteúdo é
 *                  a URL da lista (ou a própria lista). Permite trocar a lista
 *                  de qualquer navegador sem mexer na TV.
 *  - autoLoad    : abrir a lista automaticamente ao iniciar o app.
 *  - refreshHours: de quantas em quantas horas rebaixar a lista (0 = só manual).
 *  - userAgent   : User-Agent para baixar a lista/streams (quando exigido).
 *
 * O cache guarda o texto da última lista em disco para abrir rápido (e offline).
 */
class PlaylistStore(context: Context) {

    private val prefs = context.getSharedPreferences("playlist", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "playlist_cache.m3u")

    var playlistUrl: String?
        get() = prefs.getString("playlist_url", null)
        set(v) = prefs.edit().putString("playlist_url", v?.trim()).apply()

    var configUrl: String?
        get() = prefs.getString("config_url", null)
        set(v) = prefs.edit().putString("config_url", v?.trim()).apply()

    var userAgent: String?
        get() = prefs.getString("user_agent", null)
        set(v) = prefs.edit().putString("user_agent", v?.trim()).apply()

    /** URL do EPG (XMLTV, .xml ou .xml.gz). Exibição de programa atual: próximo passo. */
    var epgUrl: String?
        get() = prefs.getString("epg_url", null)
        set(v) = prefs.edit().putString("epg_url", v?.trim()).apply()

    var autoLoad: Boolean
        get() = prefs.getBoolean("auto_load", false)
        set(v) = prefs.edit().putBoolean("auto_load", v).apply()

    var refreshHours: Int
        get() = prefs.getInt("refresh_hours", 12)
        set(v) = prefs.edit().putInt("refresh_hours", v).apply()

    var lastRefreshMs: Long
        get() = prefs.getLong("last_refresh", 0L)
        set(v) = prefs.edit().putLong("last_refresh", v).apply()

    /** Nº de canais no cache (guardado para não ler o arquivo enorme só p/ contar). */
    var channelCount: Int
        get() = prefs.getInt("channel_count", 0)
        set(v) = prefs.edit().putInt("channel_count", v).apply()

    /** Última URL efetivamente carregada (atalho para a tela inicial). */
    var lastUrl: String?
        get() = prefs.getString("last_url", null)
        set(v) = prefs.edit().putString("last_url", v?.trim()).apply()

    // ---- favoritos ----
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(channelUrl: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (set.contains(channelUrl)) { set.remove(channelUrl); false }
                    else { set.add(channelUrl); true }
        prefs.edit().putStringSet("favorites", set).apply()
        return added
    }

    fun isFavorite(channelUrl: String) = favorites().contains(channelUrl)

    // ---- cache em disco ----
    fun writeCache(text: String) {
        runCatching { cacheFile.writeText(text) }
        lastRefreshMs = System.currentTimeMillis()
    }

    fun readCache(): String? =
        if (cacheFile.exists() && cacheFile.length() > 0) runCatching { cacheFile.readText() }.getOrNull() else null

    fun cacheAgeMs(): Long =
        if (lastRefreshMs > 0) System.currentTimeMillis() - lastRefreshMs else Long.MAX_VALUE

    /** true se o cache ainda está "fresco" para o intervalo configurado. */
    fun cacheFresh(): Boolean {
        val h = refreshHours
        if (h <= 0) return readCache() != null           // 0 = nunca expira sozinho
        return readCache() != null && cacheAgeMs() < h * 3_600_000L
    }

    fun clearCache() { runCatching { cacheFile.delete() }; lastRefreshMs = 0; channelCount = 0 }
}
