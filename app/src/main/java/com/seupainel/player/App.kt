package com.seupainel.player

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.seupainel.player.engine.P2pEngine
import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import kotlin.concurrent.thread

class App : Application(), ImageLoaderFactory {

    /**
     * Carregador de imagens afinado para a grade de cartazes numa TV 4K com
     * cabo de rede: cache de memória generoso, cache de disco grande e
     * PERSISTENTE (não re-baixa o mesmo pôster), e RGB_565 (metade da memória
     * por imagem, sem perda visível em pôster). Pôsteres do TMDB são imutáveis
     * pela URL, então ignoramos os cabeçalhos de cache e guardamos direto.
     */
    override fun newImageLoader(): ImageLoader {
        // Aparelho com pouca memória: cache menor (ver DeviceProfile).
        val limits = DeviceProfile.of(this)
        return ImageLoader.Builder(this)
            .crossfade(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(limits.imageMemoryPercent).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_posters"))
                    .maxSizeBytes(limits.posterDiskBytes)   // 300 MB de pôsteres em disco (100 MB com pouca memória)
                    .build()
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Updater.attach(this)
        // O motor P2P inicializa sozinho no primeiro start() (P2pEngine.init é
        // preguiçoso) — assim o app abre e toca URL direta mesmo sem config.

        // Pré-carrega a lista do cache assim que o processo sobe, para que abrir
        // qualquer seção seja instantâneo (não depende do Splash ter rodado).
        thread(name = "app-preload") {
            runCatching {
                val store = PlaylistStore(this)
                if (com.seupainel.player.playlist.XtreamCatalog.canSkipPlaylist(this, store)) {
                    // Catálogos da API já em disco: não interpreta o M3U (12 MB, ~47 mil itens), só aquece a memória.
                    com.seupainel.player.playlist.XtreamCatalog.warm(this, store, emptyList())
                } else {
                    if (PlaylistHolder.channels == null) {
                        val cache = store.readCache()
                        if (cache != null) {
                            val res = M3uParser.parse(cache)
                            if (res is M3uParser.Result.Playlist) PlaylistHolder.channels = res.channels
                        }
                    }
                    if (AppSettings(this).useXtream)
                        com.seupainel.player.playlist.XtreamCatalog.warm(this, store, PlaylistHolder.channels.orEmpty())
                }
            }
            PlaylistHolder.preloadDone = true
        }
    }

    companion object {
        @Volatile private var instance: P2pEngine? = null

        /**
         * Preencha EngineConfig com os dados do SEU painel/provedor.
         * Estes valores são os mesmos que o painel fornece para o app oficial.
         */
        fun engine(context: Context): P2pEngine =
            instance ?: synchronized(this) {
                instance ?: P2pEngine(
                    context.applicationContext,
                    P2pEngine.EngineConfig(
                        authUrl = BuildConfigValues.AUTH_URL,
                        username = BuildConfigValues.USERNAME,
                        password = BuildConfigValues.PASSWORD,
                        mkBroker = BuildConfigValues.MK_BROKER,
                        domainSuffix = BuildConfigValues.DOMAIN_SUFFIX,
                        playPort = 8902,
                    )
                ).also { instance = it }
            }
    }
}

/**
 * Em produção mova isto para BuildConfig (gradle) ou para o backend do painel.
 * NÃO comite credenciais reais no repositório.
 */
object BuildConfigValues {
    const val AUTH_URL = ""       // ex.: fornecido pelo seu provedor
    const val USERNAME = ""
    const val PASSWORD = ""
    const val MK_BROKER = ""
    const val DOMAIN_SUFFIX = ""
}
