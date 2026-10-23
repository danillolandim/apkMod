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
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_posters"))
                    .maxSizeBytes(300L * 1024 * 1024)   // 300 MB de pôsteres em disco
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        // O motor P2P inicializa sozinho no primeiro start() (P2pEngine.init é
        // preguiçoso) — assim o app abre e toca URL direta mesmo sem config.

        // Pré-carrega a lista do cache assim que o processo sobe, para que abrir
        // qualquer seção seja instantâneo (não depende do Splash ter rodado).
        thread(name = "app-preload") {
            runCatching {
                if (PlaylistHolder.channels == null) {
                    val cache = PlaylistStore(this).readCache()
                    if (cache != null) {
                        val res = M3uParser.parse(cache)
                        if (res is M3uParser.Result.Playlist) PlaylistHolder.channels = res.channels
                    }
                }
            }
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
