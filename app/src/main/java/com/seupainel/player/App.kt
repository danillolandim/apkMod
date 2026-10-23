package com.seupainel.player

import android.app.Application
import android.content.Context
import com.seupainel.player.engine.P2pEngine
import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import kotlin.concurrent.thread

class App : Application() {

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
