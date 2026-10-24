package com.seupainel.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.seupainel.player.engine.P2pEngine

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
    private lateinit var channelBar: android.widget.LinearLayout
    private lateinit var chName: TextView
    private lateinit var chEpg: TextView
    private lateinit var chLogo: android.widget.ImageView
    private lateinit var qualityBtn: TextView

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
        playerView.contentDescription = getString(R.string.a11y_player)
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

        // Cabeçalhos HTTP: UA do canal (EXTVLCOPT) ou, se ausente, o UA global
        // definido nas Configurações. Muitos provedores exigem um UA específico.
        val globalUa = com.seupainel.player.playlist.PlaylistStore(this).userAgent
        pc.setHttpHeaders(
            intent.getStringExtra(EXTRA_USER_AGENT)?.ifBlank { null } ?: globalUa,
            intent.getStringExtra(EXTRA_REFERER),
        )
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        logoUrl = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        tvgId = intent.getStringExtra(EXTRA_TVG_ID).orEmpty()
        resumeKind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        // Garante o EPG carregado (caso o player abra antes da tela de canais).
        if (isLive) com.seupainel.player.meta.Epg.ensureLoaded(
            com.seupainel.player.playlist.PlaylistStore(this).epgUrl, globalUa)
        channelBar = findViewById(R.id.channel_bar)
        chName = findViewById(R.id.ch_name)
        chEpg = findViewById(R.id.ch_epg)
        chLogo = findViewById(R.id.ch_logo)
        qualityBtn = findViewById(R.id.quality_btn)
        qualityBtn.setOnClickListener { showQualityPopup() }
        setupDock()
        // Mantém a barra do canal aberta enquanto o botão de qualidade tem foco.
        qualityBtn.setOnFocusChangeListener { _, has ->
            if (has) ui.removeCallbacks(hideChannelBar)
            else ui.postDelayed(hideChannelBar, 5_000)
        }

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

        startMs = intent.getLongExtra(EXTRA_START_MS, 0L).takeIf { it > 0 }
            ?: if (settings.resumeEnabled) resume.get(resumeKey) else 0L

        // Aspecto padrão escolhido nas Configurações.
        aspectIdx = settings.aspectIndex.coerceIn(aspectModes.indices)
        playerView.resizeMode = aspectModes[aspectIdx].first

        setupListeners()
        if (directUrl.isNotBlank()) {
            // Modo URL direta: ExoPlayer puro, sem motor P2P.
            pc.play(directUrl, subtitles = subs, startPositionMs = startMs)
        } else {
            startEngine(address)
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
                recover(detail)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    retries = 0
                    if (isLive) showChannelBar() else {
                        val q = pc.shortQualityLabel()
                        showBadge(if (title.isNotBlank()) "$title\n$q" else q, announce = true)
                    }
                }
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

    /** Backoff: 2s, 4s, 8s; depois tenta o backup; depois desiste. */
    private fun recover(reason: String) {
        if (!settings.autoRetry) { toast("Falha: $reason"); return }
        retries++
        if (retries <= 3) {
            val delay = 2000L * (1 shl (retries - 1))
            showBadge("Conectando…", announce = false)
            ui.postDelayed({
                startMs = pc.player.currentPosition.coerceAtLeast(startMs)
                if (directUrl.isNotBlank()) {
                    pc.play(directUrl, subtitles = subs, startPositionMs = startMs)
                } else {
                    engine.stop()
                    startEngine(if (usingBackup) addressBackup else address)
                }
            }, delay)
        } else if (!usingBackup && addressBackup.isNotBlank()) {
            usingBackup = true; retries = 0
            showBadge("Trocando para servidor de backup…", announce = true)
            engine.stop(); startEngine(addressBackup)
        } else {
            // erro final: mostra por mais tempo e também fixa no badge para leitura
            Toast.makeText(this, "Falha ao reproduzir: $reason", Toast.LENGTH_LONG).show()
            showBadge("Falha: $reason", announce = true, ms = 10_000)
        }
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

    // ---------- Barra de info do canal (live) ----------
    private val hideChannelBar = Runnable { channelBar.visibility = View.GONE }
    private var logoLoaded = false
    private fun showChannelBar() {
        if (inPip) return
        chName.text = title.ifBlank { "Canal" }
        val hint = if (variantUrls.size > 1) "OK: opções e qualidade" else "OK: opções"
        val epg = com.seupainel.player.meta.Epg.barText(tvgId)
        chEpg.text = epg.ifBlank { "${pc.shortQualityLabel()}  •  $hint" }
        if (logoUrl.isNotBlank() && !logoLoaded) {
            logoLoaded = true
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this).data(logoUrl).target(chLogo).crossfade(true).build()
            )
        }
        chLogo.visibility = if (logoUrl.isBlank()) View.GONE else View.VISIBLE
        if (variantUrls.size > 1) {
            qualityBtn.visibility = View.VISIBLE
            qualityBtn.text = "▾ ${variantLabels.getOrElse(variantIdx) { "" }}"
        } else qualityBtn.visibility = View.GONE
        channelBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideChannelBar)
        ui.postDelayed(hideChannelBar, 5_000)
        announce(title)
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
            qualityBtn.text = "▾ ${variantLabels.getOrElse(variantIdx) { "" }}"
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
        playerView.useController = !isInPip
        if (isInPip) {
            hud.visibility = View.GONE; badge.visibility = View.GONE
            playerView.hideController()
        } else {
            playerView.useController = true
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
        val p = pc.player
        val controllerVisible = playerView.isControllerFullyVisible
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_CAPTIONS -> { showMenu(); true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> { toggleHud(); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive) { showChannelBar(); true } else super.onKeyDown(keyCode, event)
            }
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
                else -> { openDock(); true }
            }

            // BACK: fecha o dock / os controles antes de sair
            KeyEvent.KEYCODE_BACK -> when {
                dock.visibility == View.VISIBLE -> { closeDock(); true }
                controllerVisible -> { playerView.hideController(); true }
                else -> super.onKeyDown(keyCode, event)
            }

            else -> super.onKeyDown(keyCode, event)
        }
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
        pc.play(directUrl, subtitles = subs, startPositionMs = 0)
    }

    private fun mark(selected: Boolean) = if (selected) "● " else "○ "
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** Salva posição e atualiza a fileira "Continuar" (só VOD). */
    private fun persistResume() {
        if (!settings.resumeEnabled) return
        val pos = pc.player.currentPosition
        val dur = pc.player.duration
        if (isLive || resumeKind.isBlank()) resume.save(resumeKey, pos, dur)
        else resume.remember(resumeKey, title, logoUrl, resumeKind, pos, dur)
    }

    override fun onStop() {
        super.onStop()
        persistResume()
        if (!inPip) pc.player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
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
