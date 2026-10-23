package com.seupainel.player.playlist

import android.content.Context

/** Guarda a última URL de lista usada e a lista de favoritos (por URL). */
class PlaylistStore(context: Context) {

    private val prefs = context.getSharedPreferences("playlist", Context.MODE_PRIVATE)

    var lastUrl: String?
        get() = prefs.getString("last_url", null)
        set(v) { prefs.edit().putString("last_url", v).apply() }

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(channelUrl: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (set.contains(channelUrl)) { set.remove(channelUrl); false } else { set.add(channelUrl); true }
        prefs.edit().putStringSet("favorites", set).apply()
        return added
    }

    fun isFavorite(channelUrl: String) = favorites().contains(channelUrl)
}
