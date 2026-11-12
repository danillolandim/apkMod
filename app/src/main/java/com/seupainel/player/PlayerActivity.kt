package com.seupainel.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.seupainel.player.engine.P2pEngine
import com.seupainel.player.meta.Epg
import com.seupainel.player.playlist.LiveCatalog
import com.seupainel.player.playlist.LiveChannel
import com.seupainel.player.playlist.LiveGroup
import com.seupainel.player.playlist.LiveSession
import com.seupainel.player.playlist.LiveZapper

/**
 * Activity de player para Android TV (landscape, D-pad).
 *
 * Recursos:
 *  - Retry automático com backoff + troca para endereço de backup.
 *  - "Continuar assistindo" (salva/retoma posição).
 *  - Indicador de qualidade (3s) ao iniciar e ao mudar de faixa/resolução.
 *  - Picture-in-Picture (verde ou ao sair com HOME enquanto toca).
 *  - Ciclo de aspecto (vermelho): Ajustar / Preencher / Zoom / 16:9 / 4:3.
 *  - Timer de desligar, velocidade (VOD) e tamanho de legenda no menu.
 *  - HUD de diagnóstico (INFO/azul); menu de faixas (MENU/amarelo).
 *  - Ao vivo: zapping pelo controle (CH+/CH−, ↑/↓, número, último canal) com um único ExoPlayer, e lista de
 *    canais por cima do vídeo (OK). Sempre começa na borda ao vivo: não lê nem grava posição de retomada.
 *  - Acessibilidade: anúncios TalkBack e respeito às legendas do sistema.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    private lateinit var pc: PlayerController
    private lateinit var engine: P2pEngine
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private lateinit var badge: TextView
    private lateinit var resume: ResumeStore
    private lateinit var settings: AppSettings

    private val ui = Handler(Looper.getMainLooper())
    private var retries = 0
    private var usingBackup = false
    private var isLive = false

    private var directUrl: String = ""
    private var variantUrls: List<String> = emptyList()
    private var variantLabels: List<String> = emptyList()
    private var variantIdx: Int = 0
    private lateinit var address: String
    private var addressBackup: String = ""
    private var subs: List<PlayerController.ExternalSubtitle> = emptyList()
    private var startMs: Long = 0
    private var resumeKey: String = ""
    private var title: String = ""
    private var logoUrl: String = ""
    private var tvgId: String = ""
    private var resumeKind: String = ""   // "movie" | "series" | "" (não guardar na fileira)
    private var queueUrls: List<String> = emptyList()   // próximos episódios (autoplay)
    private var queueTitles: List<String> = emptyList()
    private var queueIdx: Int = 0
    private lateinit var nextEpBar: TextView
    private var autoNextCancelled = false   // usuário cancelou o "próximo episódio"
    private lateinit var channelBar: android.widget.LinearLayout
    private lateinit var chName: TextView
    private lateinit var chEpg: TextView
    private lateinit var chLogo: android.widget.ImageView
    private lateinit var chNumber: TextView
    private lateinit var chStatus: TextView
    private lateinit var chProgress: ProgressBar
    private lateinit var chNext: TextView
    private lateinit var qualityBtn: TextView

    // Zapping (ao vivo). zapper nulo = canal único (P2P, ou sem LiveSession): comportamento anterior.
    private var zapper: LiveZapper? = null
    private var liveList: List<LiveChannel> = emptyList()
    private var liveGroups: List<LiveGroup> = emptyList()
    private var livePanel: LiveListPanel? = null
    private var pinnedStatus: String? = null   // erro que prende a faixa na tela (Voltar a fecha antes de sair)
    private var lastZapAt = 0L
    private var globalUserAgent: String? = null   // UA das Configurações: vale para canal sem UA próprio
    private val numberBuf = StringBuilder()
    private var idleWaitsKey = false   // lista aberta pela Home: o prazo de inatividade só começa na primeira tecla

    // Dock de ações (estilo dock do Mac)
    private lateinit var dock: LinearLayout
    private lateinit var dockQuality: TextView
    private lateinit var dockAudio: TextView
    private lateinit var dockSub: TextView
    private lateinit var dockAspect: TextView
    private lateinit var dockSpeed: TextView
    private lateinit var dockTimer: TextView
    private lateinit var dockPip: TextView
    private val speedSteps = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    private var speedStep = 0
    private val timerSteps = listOf(0, 15, 30, 60, 90)
    private var timerStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        hud = findViewById(R.id.hud)
        badge = findViewById(R.id.badge)
        resume = ResumeStore(this)
        settings = AppSettings(this)

        isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        pc = PlayerController(this, if (isLive) PlayerController.Mode.LIVE else PlayerController.Mode.VOD)
        pc.attach(playerView)
        // Ao vivo não usa os controles de filme do Media3: eles roubam o foco e o canal muda pelo controle remoto.
        playerView.useController = !isLive
        playerView.contentDescription = getString(if (isLive) R.string.a11y_player_live else R.string.a11y_player)
        playerView.controllerShowTimeoutMs = 4_000
        wireOptionsGear()
        engine = App.engine(this)

        directUrl = intent.getStringExtra(EXTRA_DIRECT_URL).orEmpty()
        variantUrls = intent.getStringArrayListExtra(EXTRA_VARIANT_URLS) ?: emptyList()
        variantLabels = intent.getStringArrayListExtra(EXTRA_VARIANT_LABELS) ?: emptyList()
        variantIdx = variantUrls.indexOf(directUrl).coerceAtLeast(0)
        address = intent.getStringExtra(EXTRA_P2P_ADDRESS).orEmpty()
        addressBackup = intent.getStringExtra(EXTRA_P2P_BACKUP).orEmpty()
        resumeKey = directUrl.ifBlank { address }
        if (directUrl.isBlank() && address.isBlank()) { toast("Nada para tocar"); finish(); return }
        if (isLive && directUrl.isNotBlank()) startZapSession()
        if (isLive) livePanel = LiveListPanel(this, onPick = { liveIndex ->
            ui.removeCallbacks(previewTune)
            closeList(restoreBar = false)
            zapTo(liveIndex, immediate = true)
        }, onFocus = ::armPreview)

        // Cabeçalhos HTTP: UA do canal (EXTVLCOPT) ou, se ausente, o UA global
        // definido nas Configurações. Muitos provedores exigem um UA específico.
        globalUserAgent = com.seupainel.player.playlist.PlaylistStore(this).userAgent
        pc.setHttpHeaders(
            intent.getStringExtra(EXTRA_USER_AGENT)?.ifBlank { null } ?: globalUserAgent,
            intent.getStringExtra(EXTRA_REFERER),
        )
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        logoUrl = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        tvgId = intent.getStringExtra(EXTRA_TVG_ID).orEmpty()
        resumeKind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        queueUrls = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS) ?: emptyList()
        queueTitles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES) ?: emptyList()
        queueIdx = 0
        // Garante o EPG carregado (caso o player abra antes da tela de canais).
        if (isLive) com.seupainel.player.meta.Epg.ensureLoaded(
            com.seupainel.player.playlist.PlaylistStore(this).epgUrl, globalUserAgent)
        channelBar = findViewById(R.id.channel_bar)
        chName = findViewById(R.id.ch_name)
        chEpg = findViewById(R.id.ch_epg)
        chLogo = findViewById(R.id.ch_logo)
        chNumber = findViewById(R.id.ch_number)
        chStatus = findViewById(R.id.ch_status)
        chProgress = findViewById(R.id.ch_progress)
        chNext = findViewById(R.id.ch_next)
        qualityBtn = findViewById(R.id.quality_btn)
        nextEpBar = findViewById(R.id.next_ep_bar)
        qualityBtn.setOnClickListener { showQualityPopup() }
        setupDock()

        subs = SubtitleJson.decode(intent.getStringExtra(EXTRA_SUBTITLES_JSON))
            .toExternalList()
            .ifEmpty {
                intent.getStringExtra(EXTRA_SUB_URL)?.takeIf { it.isNotBlank() }?.let {
                    listOf(PlayerController.ExternalSubtitle(
                        url = it,
                        label = intent.getStringExtra(EXTRA_SUB_LABEL) ?: "Legenda",
                        language = intent.getStringExtra(EXTRA_SUB_LANG) ?: "pt-BR",
                        makeDefault = true,
                    ))
                } ?: emptyList()
            }

        // Ao vivo sempre começa na borda ao vivo (TIME_UNSET = posição padrão); só filme/série retoma de onde parou.
        startMs = if (isLive) C.TIME_UNSET
            else intent.getLongExtra(EXTRA_START_MS, 0L).takeIf { it > 0 }
                ?: if (settings.resumeEnabled) resume.get(resumeKey) else 0L

        // Aspecto padrão escolhido nas Configurações.
        aspectIdx = settings.aspectIndex.coerceIn(aspectModes.indices)
        playerView.resizeMode = aspectModes[aspectIdx].first

        setupListeners()
        if (directUrl.isNotBlank()) {
            // Série com fila de episódios -> playlist nativa (pre-warming do
            // próximo episódio). Caso contrário, item único.
            if (queueUrls.size > 1) { pc.playQueue(queueUrls, startIndex = 0, startPositionMs = startMs); startNextEpPolling() }
            else pc.play(directUrl, subtitles = subs, startPositionMs = startMs)
        } else {
            startEngine(address)
        }
        // Entrada pela Home: já sintonizado no último canal e com a lista de canais aberta ao lado.
        if (isLive && zapper != null && intent.getBooleanExtra(EXTRA_LIVE_HOME, false)) {
            idleWaitsKey = true
            // Depois do primeiro layout: sem altura a lista não consegue centralizar o canal que está tocando.
            window.decorView.post { if (!isFinishing && !isDestroyed) openList() }
        }
    }

    private fun startEngine(addr: String) {
        engine.start(addr, object : P2pEngine.Callback {
            override fun onReady(urls: P2pEngine.PlaybackUrls) {
                ui.post {
                    val url = urls.hls ?: urls.http ?: return@post
                    pc.play(url, subtitles = subs, startPositionMs = startMs)
                }
            }
            override fun onError(message: String) {
                ui.post { recover("engine: $message") }
            }
        })
    }

    /** Faz a engrenagem do player (na tela) abrir o menu completo do app. */
    private fun wireOptionsGear() {
        fun bind() {
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.apply {
                setOnClickListener { openDock() }
                contentDescription = "Opções"
            }
            // A barra de tempo não deve capturar as setas (senão esfrega o vídeo
            // quando o usuário só quer navegar); o seek é feito pelas setas via seekBy.
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_progress)?.apply {
                isFocusable = false
                isFocusableInTouchMode = false
            }
        }
        bind()
        // Reaplica sempre que os controles reaparecem (a view pode ser recriada).
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { if (it == View.VISIBLE) bind() }
        )
    }

    private fun setupListeners() {
        pc.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val detail = when (cause) {
                    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ->
                        "HTTP ${cause.responseCode}"
                    is androidx.media3.datasource.HttpDataSource.InvalidContentTypeException ->
                        "tipo inválido: ${cause.contentType}"
                    is androidx.media3.datasource.HttpDataSource.HttpDataSourceException ->
                        "rede: ${(cause.cause?.message ?: cause.message)?.take(90) ?: cause.javaClass.simpleName}"
                    else -> "${error.errorCodeName}: ${cause?.message?.take(80) ?: ""}"
                }
                // Falha do decodificador (comum com "tunneling" em certas TVs): uma tentativa
                // automática no modo de compatibilidade, antes de desistir.
                if (directUrl.isNotBlank() && !triedCompat && isRendererError(error.errorCode)) {
                    triedCompat = true
                    pc.disableTunneling()
                    showBadge("Ajustando compatibilidade…", announce = false)
                    startMs = retryPosition()
                    pc.play(directUrl, subtitles = subs, startPositionMs = startMs)
                    return
                }
                val (kind, code) = PlayerController.classify(error)
                recover(detail, kind, code)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    retries = 0
                    // Com troca pendente a faixa já mostra o canal novo; a sintonia dele a atualiza.
                    if (isLive) { if (zapper?.isTuned != false) showChannelBar() } else {
                        val q = pc.shortQualityLabel()
                        showBadge(if (title.isNotBlank()) "$title\n$q" else q, announce = true)
                    }
                }
            }

            // Transição automática entre episódios (playlist nativa): atualiza
            // título/chave de retomada e marca o episódio anterior como visto.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (queueUrls.size <= 1) return
                val idx = pc.player.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // O item anterior terminou -> sai de "Continuar" e vira assistido.
                    val prev = idx - 1
                    queueUrls.getOrNull(prev)?.let {
                        resume.clear(it); resume.removeRecent(it); resume.markWatched(it)
                    }
                }
                queueIdx = idx
                resumeKey = queueUrls.getOrElse(idx) { resumeKey }
                directUrl = resumeKey
                title = queueTitles.getOrElse(idx) { title }
                startMs = 0
                hideNextEpBar()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    showBadge("A seguir\n$title", announce = true)
            }

            // Muda rendição (ABR) ou faixa -> mostra a qualidade nova.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) showBadge(pc.shortQualityLabel())
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = pc.audioTracks().firstOrNull { it.isSelected }?.label
                val text = pc.textTracks().firstOrNull { it.isSelected }?.label
                val parts = listOfNotNull(
                    audio?.let { "Áudio: $it" },
                    text?.let { "Legenda: $it" },
                )
                if (parts.isNotEmpty()) announce(parts.joinToString(" • "))
            }
        })
    }

    private var triedCompat = false

    private fun isRendererError(code: Int) = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
        code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
        code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED

    /**
     * Falha definitiva (arquivo inexistente, formato não suportado, acesso negado) aparece na hora.
     * Falha passageira (rede, servidor ocupado) tem backoff curto: 1s, 2s (filme) / 1s, 2s, 4s (canal);
     * depois tenta o backup; depois desiste.
     */
    private fun recover(
        reason: String,
        kind: PlaybackErrors.Kind = PlaybackErrors.Kind.NETWORK,
        httpCode: Int? = null,
    ) {
        val friendly = PlaybackErrors.message(kind, httpCode)
        if (!settings.autoRetry) {
            toast("$friendly ($reason)")
            if (isLive) showChannelBar(status = "$friendly ($reason)", autoHide = false)
            return
        }
        // Com endereço direto, só vale repetir o que é passageiro. No motor P2P tudo continua sendo repetido.
        if (directUrl.isNotBlank() && !PlaybackErrors.isTransient(kind)) { finalFailure(friendly, reason); return }
        retries++
        val maxRetries = if (isLive) 3 else 2
        if (retries <= maxRetries) {
            val delay = 1000L * (1 shl (retries - 1))
            showBadge("Conectando…", announce = false)
            ui.postDelayed(retryPlay, delay)
        } else if (!usingBackup && addressBackup.isNotBlank()) {
            usingBackup = true; retries = 0
            showBadge("Trocando para servidor de backup…", announce = true)
            engine.stop(); startEngine(addressBackup)
        } else {
            finalFailure(friendly, reason)
        }
    }

    /** Filme retoma de onde estava; canal ao vivo volta à borda ao vivo (a posição antiga já saiu da janela). */
    private fun retryPosition(): Long =
        if (isLive) C.TIME_UNSET else pc.player.currentPosition.coerceAtLeast(startMs)

    // Nome próprio para poder cancelar: ao trocar de canal, a nova tentativa do canal anterior não pode disparar.
    private val retryPlay = Runnable {
        startMs = retryPosition()
        if (directUrl.isNotBlank()) {
            pc.play(directUrl, subtitles = subs, startPositionMs = startMs)
        } else {
            engine.stop()
            startEngine(if (usingBackup) addressBackup else address)
        }
    }

    // Erro final: mostra por mais tempo e também fixa no badge para leitura.
    private fun finalFailure(friendly: String, reason: String) {
        Toast.makeText(this, "$friendly\n($reason)", Toast.LENGTH_LONG).show()
        showBadge("$friendly\n($reason)", announce = true, ms = 10_000)
        // No ao vivo a faixa fica à vista com a mensagem, para o usuário zapear para outro canal.
        if (isLive) showChannelBar(status = "$friendly ($reason)", autoHide = false)
    }

    // ---------- Badge de qualidade / status (3s) ----------
    private val hideBadge = Runnable { badge.visibility = View.GONE }
    private fun showBadge(text: String, announce: Boolean = false, ms: Long = 3_000) {
        if (inPip) return
        badge.text = text
        badge.visibility = View.VISIBLE
        ui.removeCallbacks(hideBadge)
        ui.postDelayed(hideBadge, ms)
        if (announce) announce(text)
    }

    private fun announce(text: String) = playerView.announceForAccessibility(text)

    // ---------- Faixa do canal (live) ----------
    private val hideChannelBar = Runnable { channelBar.visibility = View.GONE }
    private var shownLogo: String? = null

    /** Faixa do canal que está tocando. [status] (erro, por exemplo) ganha uma linha própria e prende a faixa. */
    private fun showChannelBar(status: String? = null, autoHide: Boolean = true) {
        if (inPip) return
        pinnedStatus = if (autoHide) null else status
        val hint = if (variantUrls.size > 1) "OK: lista de canais  •  MENU: opções e qualidade" else "OK: lista de canais  •  MENU: opções"
        val quality = if (variantUrls.size > 1) variantLabels.getOrElse(variantIdx) { "" } else null
        val number = zapper?.let { it.tuned + 1 }
        if (listOpen) {   // a lista cobre a faixa; closeList() a recoloca se houver erro fixo
            if (status == null) announce(listOfNotNull(number?.let { "Canal $it" }, title.ifBlank { null }).joinToString(", "))
            return
        }
        val now = renderChannelBar(title, logoUrl, tvgId, number, status, hint, quality)
        channelBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideChannelBar)
        if (autoHide) ui.postDelayed(hideChannelBar, 5_000)
        // O erro já é anunciado pelo badge; repetir o canal aqui só atrapalha quem usa leitor de tela.
        if (status == null) {
            announce(listOfNotNull(number?.let { "Canal $it" }, title.ifBlank { null }, now?.title).joinToString(", "))
        }
    }

    /** Mostra o canal [index] na faixa já, antes de sintonizar (a sintonia espera o usuário parar de zapear). */
    private fun previewChannelBar(index: Int) {
        val ch = liveList.getOrNull(index) ?: return
        pinnedStatus = null
        if (listOpen) return
        val quality = if (ch.variantUrls.size > 1) ch.variantLabels.firstOrNull() else null
        renderChannelBar(ch.name, ch.logo, ch.tvgId, index + 1, "Sintonizando…", null, quality)
        channelBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideChannelBar)
    }

    /**
     * Preenche a faixa e devolve o programa atual do guia (se houver), para o anúncio.
     * A [hint] de uso só aparece quando não há guia para ocupar o lugar e não há [status].
     */
    private fun renderChannelBar(
        name: String, logo: String, tvg: String, number: Int?, status: String?, hint: String?, quality: String?,
    ): Epg.Prog? {
        chNumber.text = number?.toString().orEmpty()
        chNumber.visibility = if (number == null) View.GONE else View.VISIBLE
        chName.text = name.ifBlank { "Canal" }

        val (cur, nxt) = Epg.nowNext(tvg)
        val line = status ?: hint.takeIf { cur == null && nxt == null }
        chStatus.text = line.orEmpty()
        chStatus.visibility = if (line.isNullOrBlank()) View.GONE else View.VISIBLE
        val progress = Epg.progress(tvg)
        chEpg.text = when {
            cur != null -> "${Epg.hhmm(cur.start)}–${Epg.hhmm(cur.stop)}  ${cur.title}"
            nxt != null -> "Sem informação do programa atual"
            else -> "Sem informação de programação"
        }
        chProgress.visibility = if (progress == null) View.GONE else View.VISIBLE
        chProgress.progress = ((progress ?: 0f) * 1000).toInt()
        chNext.text = nxt?.let { "A seguir ${Epg.hhmm(it.start)}  ${it.title}" }.orEmpty()
        chNext.visibility = if (nxt == null) View.GONE else View.VISIBLE

        if (logo != shownLogo) {
            shownLogo = logo
            chLogo.setImageDrawable(null)   // não deixa a logo do canal anterior enquanto a nova carrega
            if (logo.isNotBlank()) {
                coil.Coil.imageLoader(this).enqueue(
                    coil.request.ImageRequest.Builder(this).data(logo).target(chLogo).crossfade(true).build()
                )
            }
        }
        chLogo.visibility = if (logo.isBlank()) View.GONE else View.VISIBLE

        qualityBtn.visibility = if (quality == null) View.GONE else View.VISIBLE
        qualityBtn.text = "Qualidade: ${quality.orEmpty()}"
        return cur
    }

    /** Menu suspenso compacto de qualidade, ancorado no botão (não cobre a tela). */
    private fun showQualityPopup() {
        if (variantUrls.size <= 1) return
        ui.removeCallbacks(hideChannelBar)
        val ctx = android.view.ContextThemeWrapper(this, R.style.Theme_Player_Popup)
        val pm = android.widget.PopupMenu(ctx, qualityBtn)
        variantLabels.forEachIndexed { i, label ->
            pm.menu.add(0, i, i, (if (i == variantIdx) "● " else "○ ") + label)
        }
        pm.setOnMenuItemClickListener { item ->
            switchVariant(item.itemId)
            qualityBtn.text = "Qualidade: ${variantLabels.getOrElse(variantIdx) { "" }}"
            true
        }
        pm.setOnDismissListener { ui.postDelayed(hideChannelBar, 5_000) }
        pm.show()
    }


    // ---------- Aspecto (vermelho) ----------
    private val aspectModes = PlayerController.ASPECTS
    private var aspectIdx = 0
    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectModes.size
        val (mode, label) = aspectModes[aspectIdx]
        playerView.resizeMode = mode
        showBadge("Aspecto: $label", announce = true)
    }

    // ---------- Picture-in-Picture (verde / HOME) ----------
    private var inPip = false
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val vs = pc.player.videoSize
        val ratio = if (vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height) else Rational(16, 9)
        // Android exige proporção entre 1:2.39 e 2.39:1
        val safe = ratio.toFloat().coerceIn(0.4184f, 2.39f)
        val r = Rational((safe * 1000).toInt(), 1000)
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(r).build())
        } catch (_: Exception) { /* device sem suporte real */ }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pc.player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        inPip = isInPip
        playerView.useController = !isInPip && !isLive
        if (isInPip) {
            closeList(restoreBar = false)
            hud.visibility = View.GONE; badge.visibility = View.GONE
            channelBar.visibility = View.GONE
            playerView.hideController()
        } else {
            playerView.useController = !isLive
        }
    }

    // ---------- Timer de desligar ----------
    private val sleepRunnable = Runnable { pc.player.pause(); finish() }
    private fun setSleepTimer(minutes: Int) {
        ui.removeCallbacks(sleepRunnable)
        if (minutes > 0) {
            ui.postDelayed(sleepRunnable, minutes * 60_000L)
            showBadge("Desliga em $minutes min", announce = true)
        } else showBadge("Timer desligado", announce = true)
    }

    // ---------- HUD (INFO / azul) ----------
    private var hudOn = false
    private val hudTick = object : Runnable {
        override fun run() {
            if (!hudOn) return
            val s = pc.stats()
            hud.text = buildString {
                appendLine("RES    : ${s.width}x${s.height}")
                appendLine("BITRATE: ${fmtMbps(s.videoBitrate)}")
                appendLine("CODEC  : ${s.codec ?: "-"}")
                appendLine("REDE   : ${"%.1f".format(s.networkKbps / 1000.0)} Mbps")
                appendLine("BUFFER : ${s.bufferedMs / 1000}s")
                append("DROP   : ${s.droppedFrames}")
            }
            ui.postDelayed(this, 1000)
        }
    }
    private fun toggleHud() {
        hudOn = !hudOn
        hud.visibility = if (hudOn) View.VISIBLE else View.GONE
        if (hudOn) ui.post(hudTick)
    }
    private fun fmtMbps(b: Int) = if (b > 0) "${"%.1f".format(b / 1_000_000.0)} Mbps" else "-"

    // ---------- Teclas do controle ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Lista aberta: o que a lista (e o dispatchKeyEvent) não tratou não pode zapear nem abrir o dock.
        if (isLive && listOpen) return true
        // Com o dock aberto as setas navegam nele; fora isso, o ao vivo tem o mapa de teclas de TV.
        if (isLive && dock.visibility != View.VISIBLE) liveKeyDown(keyCode, event)?.let { return it }
        val p = pc.player
        val controllerVisible = playerView.isControllerFullyVisible
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_CAPTIONS -> { showMenu(); true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> { toggleHud(); true }
            KeyEvent.KEYCODE_PROG_RED -> { cycleAspect(); true }
            KeyEvent.KEYCODE_PROG_GREEN -> { enterPip(); true }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (p.isPlaying) p.pause() else p.play(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); true }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(+60_000); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-60_000); true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { seekBy(+10 * 60_000); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { seekBy(-10 * 60_000); true }

            // Setas = avançar/voltar 10s (sempre, a menos que o dock esteja aberto).
            // Com o dock fechado as setas NUNCA mexem na seekbar — elas são o seek.
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (dock.visibility != View.VISIBLE) { seekBy(+seekStepMs()); true } else super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (dock.visibility != View.VISIBLE) { seekBy(-seekStepMs()); true } else super.onKeyDown(keyCode, event)

            // OK: abre o dock de ações (qualidade, áudio, legenda…) no ao vivo E no VOD.
            // Com o dock aberto, o OK seleciona o botão em foco.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                dock.visibility == View.VISIBLE -> super.onKeyDown(keyCode, event)
                // Contagem do próximo episódio visível: OK pula pro próximo agora.
                nextEpBar.visibility == View.VISIBLE && p.hasNextMediaItem() -> {
                    hideNextEpBar(); p.seekToNextMediaItem(); true
                }
                else -> { openDock(); true }
            }

            // BACK: cancela o "próximo episódio" / fecha o dock / os controles antes de sair
            KeyEvent.KEYCODE_BACK -> when {
                dock.visibility == View.VISIBLE -> { closeDock(); true }
                nextEpBar.visibility == View.VISIBLE -> {
                    autoNextCancelled = true
                    pc.player.pauseAtEndOfMediaItems = true   // não emenda sozinho
                    hideNextEpBar(); showBadge("Autoplay cancelado", announce = true); true
                }
                controllerVisible -> { playerView.hideController(); true }
                else -> super.onKeyDown(keyCode, event)
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Mapa de teclas de TV ao vivo. Devolve null para as teclas que seguem o tratamento comum. */
    private fun liveKeyDown(keyCode: Int, event: KeyEvent?): Boolean? {
        val firstPress = event == null || event.repeatCount == 0
        digitOf(keyCode)?.let { d ->
            if (zapper == null) return null
            if (firstPress) typeDigit(d)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> zapStep(+1, event)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zapStep(-1, event)
            // Sem seek no ao vivo: as setas laterais são consumidas para não mexer no foco.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (firstPress) {
                cancelNumber()
                zapper?.last()?.let { zapTo(it) }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (numberBuf.isNotEmpty()) applyNumber()
                else if (firstPress) event?.startTracking()   // curto age no onKeyUp, longo no onKeyLongPress
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_CAPTIONS ->
                if (firstPress) openDock()
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE ->
                if (firstPress) event?.startTracking()        // curto = faixa, longo = diagnóstico
            KeyEvent.KEYCODE_BACK -> return when {
                numberBuf.isNotEmpty() -> { cancelNumber(); true }
                // Faixa que está só no auto-hide não segura o Voltar; a presa por erro fecha antes de sair.
                pinnedStatus != null && channelBar.visibility == View.VISIBLE -> {
                    pinnedStatus = null
                    channelBar.visibility = View.GONE
                    true
                }
                else -> null
            }
            else -> return null
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isLive && event != null && event.isTracking && !event.isCanceled && dock.visibility != View.VISIBLE) {
            when (keyCode) {
                // OK curto abre a lista de canais; o dock fica no OK longo e no MENU. Sem lista (canal único), OK abre o dock.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (zapper != null) openList() else openDock()
                    return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> { showChannelBar(); return true }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isLive && dock.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openDock(); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> { toggleHud(); return true }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // Antes das views: com a lista aberta o ListView consome ↑/↓/OK/PgUp/PgDn, e aqui ficam as teclas de TV.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLive && listOpen && event.action == KeyEvent.ACTION_DOWN) {
            armListIdle()
            if (listKeyDown(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun listKeyDown(event: KeyEvent): Boolean {
        val panel = livePanel ?: return false
        val key = event.keyCode
        return when {
            digitOf(key) != null -> liveKeyDown(key, event) == true
            else -> when (key) {
                // A lista segue o canal que o usuário zapeia (a seleção acompanha, em zapTo).
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_LAST_CHANNEL ->
                    liveKeyDown(key, event) == true
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (panel.channelsHaveFocus) panel.focusGroups(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (panel.groupsHaveFocus) panel.focusChannels(); true }
                // Com número em digitação o OK confirma o número; senão é do ListView (sintoniza o canal em foco).
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (numberBuf.isNotEmpty()) { applyNumber(); true } else false
                KeyEvent.KEYCODE_BACK -> { closeList(); true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_CAPTIONS -> {
                    closeList(restoreBar = false)
                    openDock()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Lista de canais (ao vivo) ----------
    private val listOpen: Boolean get() = livePanel?.isOpen == true
    private val listIdleClose = Runnable { closeList() }

    private fun openList() {
        val panel = livePanel ?: return
        val z = zapper ?: return
        ui.removeCallbacks(hideChannelBar)
        channelBar.visibility = View.GONE
        val playing = z.current
        panel.open(liveList, liveGroups, playing, LiveCatalog.groupIndexOf(liveGroups, liveList[playing]))
        if (!idleWaitsKey) armListIdle()
    }

    /** [restoreBar]: devolve a faixa se ela estava presa por um erro quando a lista abriu. */
    private fun closeList(restoreBar: Boolean = true) {
        if (!listOpen) return
        livePanel?.close()
        ui.removeCallbacks(listIdleClose)
        ui.removeCallbacks(previewTune)
        val pinned = pinnedStatus
        if (restoreBar && pinned != null) showChannelBar(status = pinned, autoHide = false)
    }

    private fun armListIdle() {
        idleWaitsKey = false
        ui.removeCallbacks(listIdleClose)
        ui.postDelayed(listIdleClose, LIST_IDLE_MS)
    }

    /**
     * Com a lista aberta, o canal em foco por [PREVIEW_DELAY_MS] passa a tocar (no mesmo player). Cada movimento do
     * foco reinicia a espera, então passar por várias linhas não abre conexão em nenhuma delas.
     */
    private fun armPreview() {
        ui.removeCallbacks(previewTune)
        ui.postDelayed(previewTune, PREVIEW_DELAY_MS)
    }

    private val previewTune = Runnable {
        val panel = livePanel ?: return@Runnable
        val z = zapper ?: return@Runnable
        // Lê o foco de agora, não o do evento que armou a espera: ele pode ter mudado, ou ido para as categorias.
        if (!panel.isOpen || !panel.channelsHaveFocus) return@Runnable
        val index = panel.focusedChannel() ?: return@Runnable
        if (index == z.tuned) return@Runnable
        z.jumpTo(index)
        tune(index)
        panel.markPlaying(index)
    }

    // ---------- Zapping (ao vivo) ----------
    private fun startZapSession() {
        val index = LiveSession.indexOf(directUrl) ?: return
        liveList = LiveSession.channels
        liveGroups = LiveSession.groups
        zapper = LiveZapper(liveList.size, index)
        val ch = liveList[index]
        // Aberto por uma lista sem as outras qualidades: o canal da sessão sabe todas.
        if (variantUrls.size <= 1 && ch.variantUrls.size > 1 && directUrl in ch.variantUrls) {
            variantUrls = ch.variantUrls
            variantLabels = ch.variantLabels
            variantIdx = variantUrls.indexOf(directUrl).coerceAtLeast(0)
        }
        settings.lastLiveChannel = LiveCatalog.keyOf(ch)
    }

    /** CH+/CH−: [dir] > 0 vai ao próximo canal (dá a volta no fim da lista), < 0 ao anterior. */
    private fun zapStep(dir: Int, event: KeyEvent?) {
        val z = zapper
        if (z == null) { showChannelBar(); return }   // canal único: não há para onde zapear
        val now = SystemClock.uptimeMillis()
        // Segurar a tecla repete rápido demais para ler os canais que passam.
        if (event != null && event.repeatCount > 0 && now - lastZapAt < ZAP_REPEAT_MIN_MS) return
        lastZapAt = now
        cancelNumber()
        val index = (if (dir > 0) z.next() else z.prev()) ?: return
        zapTo(index)
    }

    /**
     * Atualiza a faixa na hora e sintoniza só depois de [ZAP_TUNE_DELAY_MS] sem outra troca; [immediate] é para
     * a escolha explícita na lista (OK), que não tem o que esperar.
     */
    private fun zapTo(index: Int, immediate: Boolean = false) {
        if (liveList.getOrNull(index) == null) return
        zapper?.jumpTo(index)
        previewChannelBar(index)
        if (listOpen) livePanel?.follow(index)
        ui.removeCallbacks(tuneRunnable)
        if (immediate) tuneRunnable.run() else ui.postDelayed(tuneRunnable, ZAP_TUNE_DELAY_MS)
    }

    private val tuneRunnable = Runnable {
        val z = zapper ?: return@Runnable
        // Voltou ao canal que já está tocando: só restaura a faixa, sem reconectar.
        if (z.isTuned) showChannelBar() else tune(z.current)
    }

    /** Troca de canal no MESMO player: o provedor costuma liberar uma conexão só, então nada de segundo player. */
    private fun tune(index: Int) {
        val ch = liveList.getOrNull(index) ?: return
        zapper?.commit()
        ui.removeCallbacks(retryPlay)
        retries = 0; triedCompat = false; usingBackup = false
        pinnedStatus = null
        startMs = C.TIME_UNSET   // sempre na borda ao vivo
        subs = emptyList()
        directUrl = ch.url
        title = ch.name
        logoUrl = ch.logo
        tvgId = ch.tvgId
        variantUrls = ch.variantUrls
        variantLabels = ch.variantLabels
        variantIdx = variantUrls.indexOf(directUrl).coerceAtLeast(0)
        pc.setHttpHeaders(ch.userAgent?.ifBlank { null } ?: globalUserAgent, ch.referer)
        pc.play(directUrl, startPositionMs = startMs)
        settings.lastLiveChannel = LiveCatalog.keyOf(ch)
    }

    // ---------- Número do canal (0–9) ----------
    private val confirmNumber = Runnable { applyNumber() }

    private fun digitOf(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    private fun typeDigit(d: Int) {
        if (numberBuf.length >= MAX_NUMBER_DIGITS) numberBuf.clear()   // quinto dígito recomeça o número
        numberBuf.append(d)
        showBadge("Canal $numberBuf", ms = NUMBER_CONFIRM_MS + 1_000)
        ui.removeCallbacks(confirmNumber)
        ui.postDelayed(confirmNumber, NUMBER_CONFIRM_MS)
    }

    private fun applyNumber() {
        ui.removeCallbacks(confirmNumber)
        if (numberBuf.isEmpty()) return
        val number = numberBuf.toString().toInt()
        numberBuf.clear()
        val index = zapper?.byNumber(number)
        if (index == null) showBadge("Canal $number não existe", announce = true) else zapTo(index)
    }

    private fun cancelNumber() {
        if (numberBuf.isEmpty()) return
        numberBuf.clear()
        ui.removeCallbacks(confirmNumber)
        ui.removeCallbacks(hideBadge)
        badge.visibility = View.GONE
    }

    // ---- "Próximo episódio em N" (contagem nos últimos segundos) ----
    private val nextLeadMs = 10_000L
    private val nextEpPoll = object : Runnable {
        override fun run() {
            maybeShowNextCountdown()
            ui.postDelayed(this, 500)
        }
    }

    private fun startNextEpPolling() {
        ui.removeCallbacks(nextEpPoll)
        if (queueUrls.size > 1) ui.postDelayed(nextEpPoll, 1_000)
    }

    private fun maybeShowNextCountdown() {
        val p = pc.player
        if (queueUrls.size <= 1 || autoNextCancelled || inPip ||
            !p.hasNextMediaItem() || dock.visibility == View.VISIBLE) {
            hideNextEpBar(); return
        }
        val dur = p.duration
        val remain = dur - p.currentPosition
        if (dur > 0 && p.isPlaying && remain in 1_000..nextLeadMs) {
            val secs = ((remain + 999) / 1000).toInt()
            val next = queueTitles.getOrElse(queueIdx + 1) { "próximo episódio" }
            nextEpBar.text = "Próximo em ${secs}s  ·  $next\nOK: agora   •   Voltar: cancelar"
            if (nextEpBar.visibility != View.VISIBLE) nextEpBar.visibility = View.VISIBLE
        } else hideNextEpBar()
    }

    private fun hideNextEpBar() {
        if (nextEpBar.visibility != View.GONE) nextEpBar.visibility = View.GONE
    }

    private fun seekStepMs(): Long = settings.seekStepSec.coerceIn(5, 300) * 1000L

    private fun seekBy(deltaMs: Long) {
        if (isLive && !pc.player.isCurrentMediaItemSeekable) { showBadge("Ao vivo"); return }
        val p = pc.player
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(target)
        val sign = if (deltaMs >= 0) "+" else "−"
        showBadge("$sign${kotlin.math.abs(deltaMs) / 1000}s  •  ${fmtTime(target)}  /  ${fmtTime(p.duration.coerceAtLeast(0))}")
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    // ---------- Menu (MENU / amarelo) ----------
    // ---------- Dock de ações (estilo dock do Mac) ----------
    private fun setupDock() {
        dock = findViewById(R.id.player_dock)
        dockQuality = findViewById(R.id.dock_quality)
        dockAudio = findViewById(R.id.dock_audio)
        dockSub = findViewById(R.id.dock_sub)
        dockAspect = findViewById(R.id.dock_aspect)
        dockSpeed = findViewById(R.id.dock_speed)
        dockTimer = findViewById(R.id.dock_timer)
        dockPip = findViewById(R.id.dock_pip)
        dockQuality.setOnClickListener { cycleQuality() }
        dockAudio.setOnClickListener { cycleAudio() }
        dockSub.setOnClickListener { cycleSub() }
        dockAspect.setOnClickListener { cycleAspect(); updateDock() }
        dockSpeed.setOnClickListener { cycleSpeed() }
        dockTimer.setOnClickListener { cycleTimer() }
        dockPip.setOnClickListener { closeDock(); enterPip() }
    }

    private fun openDock() {
        closeList(restoreBar = false)
        playerView.hideController()
        ui.removeCallbacks(hideChannelBar); channelBar.visibility = View.GONE
        dockSpeed.visibility = if (isLive) View.GONE else View.VISIBLE
        updateDock()
        if (dock.visibility != View.VISIBLE) {
            dock.visibility = View.VISIBLE
            dock.alpha = 0f
            dock.post {
                dock.translationY = dock.height.toFloat()
                dock.animate().translationY(0f).alpha(1f).setDuration(200).start()
            }
        }
        dockQuality.requestFocus()
    }

    private fun closeDock() {
        if (dock.visibility != View.VISIBLE) return
        dock.animate().translationY(dock.height.toFloat()).alpha(0f).setDuration(180)
            .withEndAction { dock.visibility = View.GONE; dock.translationY = 0f; dock.alpha = 1f }.start()
    }

    private fun dockLabel(action: String, value: String) = "$action\n$value"

    private fun updateDock() {
        dockQuality.text = dockLabel("Qualidade", qualityValue())
        dockAudio.text = dockLabel("Áudio", audioValue())
        dockSub.text = dockLabel("Legenda", subValue())
        dockAspect.text = dockLabel("Aspecto", aspectModes[aspectIdx].second)
        dockSpeed.text = dockLabel("Velocidade", "${speedSteps[speedStep]}x")
        dockTimer.text = dockLabel("Timer", if (timerSteps[timerStep] == 0) "Off" else "${timerSteps[timerStep]}m")
        dockPip.text = "PiP"
    }

    private fun qualityValue(): String =
        if (variantUrls.size > 1) variantLabels.getOrElse(variantIdx) { "—" }
        else pc.videoTracks().firstOrNull { it.isSelected }?.label?.substringBefore(" ") ?: "Auto"

    private fun audioValue(): String = pc.audioTracks().firstOrNull { it.isSelected }?.label ?: "—"
    private fun subValue(): String = pc.textTracks().firstOrNull { it.isSelected }?.label ?: "Off"

    private fun cycleQuality() {
        if (variantUrls.size > 1) switchVariant((variantIdx + 1) % variantUrls.size)
        else {
            val v = pc.videoTracks()
            if (v.size > 1) {
                val sel = v.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                pc.selectTrack(v[(sel + 1) % v.size])
            }
        }
        ui.postDelayed({ updateDock() }, 150)
    }

    private fun cycleAudio() {
        val a = pc.audioTracks()
        if (a.size > 1) {
            val sel = a.indexOfFirst { it.isSelected }.coerceAtLeast(0)
            val next = a[(sel + 1) % a.size]
            pc.selectTrack(next); showBadge("Áudio: ${next.label}")
        }
        ui.postDelayed({ updateDock() }, 150)
    }

    private fun cycleSub() {
        val t = pc.textTracks()
        val sel = t.indexOfFirst { it.isSelected }
        when {
            t.isEmpty() -> {}
            sel < 0 -> { pc.selectTrack(t[0]); showBadge("Legenda: ${t[0].label}") }
            sel >= t.size - 1 -> { pc.disableSubtitles(); showBadge("Legenda desligada") }
            else -> { pc.selectTrack(t[sel + 1]); showBadge("Legenda: ${t[sel + 1].label}") }
        }
        ui.postDelayed({ updateDock() }, 150)
    }

    private fun cycleSpeed() {
        speedStep = (speedStep + 1) % speedSteps.size
        pc.setSpeed(speedSteps[speedStep]); showBadge("Velocidade ${speedSteps[speedStep]}x")
        updateDock()
    }

    private fun cycleTimer() {
        timerStep = (timerStep + 1) % timerSteps.size
        setSleepTimer(timerSteps[timerStep])
        updateDock()
    }

    /** Menu compacto: 1 linha por tipo (com o valor atual); cada uma abre um submenu curto. */
    private fun showMenu() {
        val video = pc.videoTracks()
        val audio = pc.audioTracks()
        val text = pc.textTracks()
        val options = ArrayList<Pair<String, () -> Unit>>()

        if (variantUrls.size > 1) {
            options += "Qualidade: ${variantLabels.getOrElse(variantIdx) { "—" }}" to { showQualityMenu() }
        } else if (video.size > 1) {
            options += "Qualidade" to { showVideoTrackMenu() }
        }
        if (audio.size > 1) {
            val cur = audio.firstOrNull { it.isSelected }?.label ?: "—"
            options += "Áudio: $cur" to { showAudioMenu() }
        }
        val curSub = text.firstOrNull { it.isSelected }?.label ?: "desligada"
        options += "Legenda: $curSub" to { showSubtitleMenu() }
        options += "Tamanho da legenda" to { showSubtitleSizeMenu() }
        if (!isLive) options += "Velocidade" to { showSpeedMenu() }
        options += "Aspecto: ${aspectModes[aspectIdx].second}" to { cycleAspect() }
        options += "Timer de desligar" to { showSleepMenu() }
        options += "Picture-in-Picture" to { enterPip() }

        menu("Opções", options)
    }

    /** Helper para montar um diálogo de lista a partir de pares (rótulo, ação). */
    private fun menu(title: String, options: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun showQualityMenu() {
        val options = variantUrls.indices.map { i ->
            "${mark(i == variantIdx)}${variantLabels.getOrElse(i) { "Opção ${i + 1}" }}" to { switchVariant(i) }
        }
        menu("Qualidade do canal", options)
    }

    private fun showVideoTrackMenu() {
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += "Automática" to { pc.setAutoQuality(); showBadge("Qualidade automática", true) }
        pc.videoTracks().sortedByDescending { it.label }.forEach { t ->
            options += "${mark(t.isSelected)}${t.label}" to { pc.selectTrack(t); showBadge("Qualidade: ${t.label}", true) }
        }
        menu("Qualidade", options)
    }

    private fun showAudioMenu() {
        val options = pc.audioTracks().map { t ->
            "${mark(t.isSelected)}${t.label}" to { pc.selectTrack(t); showBadge("Áudio: ${t.label}", true) }
        }
        menu("Áudio", options)
    }

    private fun showSubtitleMenu() {
        val options = ArrayList<Pair<String, () -> Unit>>()
        val text = pc.textTracks()
        options += "${mark(text.none { it.isSelected })}Desligar" to { pc.disableSubtitles(); showBadge("Legenda desligada", true) }
        text.forEach { t ->
            options += "${mark(t.isSelected)}${t.label}" to { pc.selectTrack(t); showBadge("Legenda: ${t.label}", true) }
        }
        menu("Legenda", options)
    }

    private fun showSubtitleSizeMenu() {
        val sizes = listOf("Pequena" to 16f, "Média" to 20f, "Grande" to 26f, "Enorme" to 34f)
        AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle("Tamanho da legenda")
            .setItems(sizes.map { it.first }.toTypedArray()) { _, i ->
                pc.setSubtitleSizeSp(playerView, sizes[i].second)
                showBadge("Legenda: ${sizes[i].first}", true)
            }.show()
    }

    private fun showSpeedMenu() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle("Velocidade")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i ->
                pc.setSpeed(speeds[i]); showBadge("Velocidade ${speeds[i]}x", true)
            }.show()
    }

    private fun showSleepMenu() {
        val mins = listOf(0, 15, 30, 60, 90)
        AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle("Timer de desligar")
            .setItems(mins.map { if (it == 0) "Desligado" else "$it min" }.toTypedArray()) { _, i ->
                setSleepTimer(mins[i])
            }.show()
    }

    /** Troca para outro stream de qualidade do mesmo canal ao vivo. */
    private fun switchVariant(i: Int) {
        if (i !in variantUrls.indices || i == variantIdx) return
        variantIdx = i
        directUrl = variantUrls[i]
        retries = 0
        showBadge("Qualidade: ${variantLabels.getOrElse(i) { "" }}", announce = true)
        pc.play(directUrl, subtitles = subs, startPositionMs = if (isLive) C.TIME_UNSET else 0)
    }

    private fun mark(selected: Boolean) = if (selected) "● " else "○ "
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** Salva posição e atualiza a fileira "Continuar" (só VOD). */
    private fun persistResume() {
        if (isLive || !settings.resumeEnabled) return
        val pos = pc.player.currentPosition
        val dur = pc.player.duration
        if (resumeKind.isBlank()) { resume.save(resumeKey, pos, dur); return }
        resume.remember(resumeKey, title, logoUrl, resumeKind, pos, dur)
        // Episódio de série concluído -> marca como assistido (✓ na tela da série).
        if (resumeKind == "series" && dur > 0 && pos > dur - 15_000) resume.markWatched(resumeKey)
    }

    override fun onStart() {
        super.onStart()
        com.seupainel.player.net.Net.playbackActive = true   // trabalhos de fundo esperam
    }

    override fun onStop() {
        super.onStop()
        com.seupainel.player.net.Net.playbackActive = false
        closeList(restoreBar = false)
        persistResume()
        if (!inPip) pc.player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        com.seupainel.player.net.Net.playbackActive = false
        persistResume()
        pc.release()
        if (directUrl.isBlank()) engine.stop()
    }

    companion object {
        const val EXTRA_DIRECT_URL = "direct_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_TVG_ID = "tvg_id"
        const val EXTRA_KIND = "kind"   // "movie" | "series" para a fileira "Continuar"
        const val EXTRA_QUEUE_URLS = "queue_urls"     // próximos episódios (autoplay)
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_P2P_ADDRESS = "p2p_address"
        const val EXTRA_P2P_BACKUP = "p2p_backup"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_SUBTITLES_JSON = "subtitles_json"
        const val EXTRA_VARIANT_URLS = "variant_urls"
        const val EXTRA_VARIANT_LABELS = "variant_labels"
        const val EXTRA_SUB_URL = "sub_url"
        const val EXTRA_SUB_LABEL = "sub_label"
        const val EXTRA_SUB_LANG = "sub_lang"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_LIVE_HOME = "live_home"   // ao vivo aberto pela Home: lista de canais já aberta

        private const val ZAP_TUNE_DELAY_MS = 300L       // espera antes de abrir conexão no canal novo
        private const val ZAP_REPEAT_MIN_MS = 150L       // intervalo mínimo entre trocas com a tecla segurada
        private const val NUMBER_CONFIRM_MS = 1_500L     // sem novo dígito, o número digitado é confirmado
        private const val MAX_NUMBER_DIGITS = 4
        private const val LIST_IDLE_MS = 30_000L          // a lista de canais fecha sozinha sem tecla
        private const val PREVIEW_DELAY_MS = 800L         // foco parado num canal da lista: ele passa a tocar

        /** Player ao vivo no canal [ch] da [LiveSession], com a lista de canais aberta (entrada pela Home). */
        fun liveHomeIntent(context: android.content.Context, ch: LiveChannel) =
            android.content.Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_DIRECT_URL, ch.url)
                putExtra(EXTRA_IS_LIVE, true)
                putExtra(EXTRA_TITLE, ch.name)
                putExtra(EXTRA_LOGO, ch.logo)
                putExtra(EXTRA_TVG_ID, ch.tvgId)
                ch.userAgent?.let { putExtra(EXTRA_USER_AGENT, it) }
                ch.referer?.let { putExtra(EXTRA_REFERER, it) }
                if (ch.variantUrls.size > 1) {
                    putStringArrayListExtra(EXTRA_VARIANT_URLS, ArrayList(ch.variantUrls))
                    putStringArrayListExtra(EXTRA_VARIANT_LABELS, ArrayList(ch.variantLabels))
                }
                putExtra(EXTRA_LIVE_HOME, true)
            }

        fun intent(context: android.content.Context, source: PanelSource) =
            android.content.Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_P2P_ADDRESS, source.address)
                putExtra(EXTRA_P2P_BACKUP, source.addressBackup)
                putExtra(EXTRA_IS_LIVE, source.isLive)
                putExtra(EXTRA_SUBTITLES_JSON, SubtitleJson.encode(source.subtitles))
                putExtra(EXTRA_START_MS, source.startPositionMs)
            }
    }
}
