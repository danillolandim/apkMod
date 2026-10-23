package com.seupainel.player.engine

import android.content.Context
import com.tvbus.engine.TVCore
import com.tvbus.engine.TVListener
import org.json.JSONObject

/**
 * Wrapper em volta do motor P2P TVBus (TVCore / libtvcore.so).
 *
 * O motor NÃO expõe o vídeo como uma URL remota. Ele resolve o endereço P2P
 * e sobe um servidor HTTP local (porta de "play", padrão 8902) que serve um
 * HLS de 127.0.0.1. Quem toca de fato é o ExoPlayer, apontado para essa URL.
 *
 * Fluxo:
 *   start(address)  ->  onInited -> onStart -> onPrepared(json)
 *   O json de onPrepared/onStart traz as URLs locais, p.ex.:
 *     { "hls":  "http://127.0.0.1:8902/<id>/index.m3u8",
 *       "http": "http://127.0.0.1:8902/mpegts_live",
 *       "port": 8902, ... }
 *
 * IMPORTANTE: o binário libtvcore.so / libgojni.so e as credenciais
 * (authUrl, username, password, mkBroker, domainSuffix) são do SEU provedor
 * de painel. Coloque o .so em jniLibs/ e os valores em EngineConfig. Este
 * código só fala com a API pública do motor — não embute nem quebra nada.
 */
class P2pEngine(
    private val appContext: Context,
    private val config: EngineConfig,
) {

    data class EngineConfig(
        val authUrl: String,
        val username: String,
        val password: String,
        val mkBroker: String? = null,
        val domainSuffix: String? = null,
        val playPort: Int = 8902,
        val servPort: Int = 0,
        /** RM_STANDALONE=0, RM_MASTER=1, RM_CLIENT=2 */
        val runningMode: Int = 0,
    )

    /** URLs locais devolvidas pelo motor depois que o stream fica pronto. */
    data class PlaybackUrls(val hls: String?, val http: String?, val port: Int)

    interface Callback {
        fun onReady(urls: PlaybackUrls)
        fun onInfo(progressJson: String) {}
        fun onError(message: String) {}
        fun onStopped() {}
    }

    // Carregado só quando precisar: abrir o app em modo URL direta não toca no
    // motor nem carrega o .so.
    private val core: TVCore? by lazy { runCatching { TVCore.getInstance() }.getOrNull() }
    private var callback: Callback? = null
    @Volatile private var started = false
    @Volatile private var inited = false

    private val listener = object : TVListener {
        override fun onInited(s: String?) { /* motor pronto para start() */ }

        override fun onStart(s: String?)    { s?.let { tryEmit(it) } }
        override fun onPrepared(s: String?) { s?.let { tryEmit(it) } }

        override fun onInfo(s: String?) { s?.let { callback?.onInfo(it) } }
        override fun onStop(s: String?) { callback?.onStopped() }
        override fun onQuit(s: String?) { callback?.onStopped() }
    }

    private fun tryEmit(json: String) {
        val urls = parseUrls(json) ?: return
        if (urls.hls != null || urls.http != null) callback?.onReady(urls)
    }

    /** Inicializa o motor uma vez. É chamado automaticamente no primeiro start(). */
    @Synchronized
    fun init() {
        if (inited) return
        val c = core ?: run { callback?.onError("TVCore indisponível (libtvcore.so não carregou)"); return }
        if (config.authUrl.isBlank()) {
            callback?.onError("Motor P2P sem configuração (App.kt → BuildConfigValues)")
            return
        }
        inited = true
        c.setTVListener(listener)
        c.setRunningMode(config.runningMode)
        c.setPlayPort(config.playPort)
        c.setServPort(config.servPort)
        c.setAuthUrl(config.authUrl)
        c.setUsername(config.username)
        c.setPassword(config.password)
        config.mkBroker?.let { c.setMKBroker(it) }
        config.domainSuffix?.let { c.setDomainSuffix(it) }
        c.init(appContext)
        c.run()
    }

    /**
     * Resolve e começa a baixar/servir o conteúdo.
     * @param address endereço P2P do seu painel (o mesmo que o app original
     *                passa hoje para TVCore.start()).
     * @param cb      chamado quando a URL local de play estiver pronta.
     */
    fun start(address: String, cb: Callback) {
        callback = cb
        init()
        if (!inited) return // init já reportou o erro no callback
        val c = core ?: run { cb.onError("TVCore indisponível"); return }
        if (started) c.stop()
        c.start(address)
        started = true
    }

    fun stop() {
        if (!started) return
        started = false
        core?.stop()
    }

    fun release() {
        stop()
        if (inited) core?.quit()
        callback = null
    }

    private fun parseUrls(json: String): PlaybackUrls? = try {
        val o = JSONObject(json)
        val port = o.optInt("port", config.playPort)
        val hls = o.optString("hls", "").takeIf { it.isNotBlank() }
        val http = o.optString("http", "").ifBlank { o.optString("url", "") }
            .takeIf { it.isNotBlank() }
        PlaybackUrls(hls, http, port)
    } catch (_: Exception) {
        null
    }
}
