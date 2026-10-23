package com.seupainel.player

import android.app.AlertDialog
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
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
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
    private lateinit var channelBar: android.widget.LinearLayout
    private lateinit var chName: TextView
    private lateinit var chEpg: TextView
    private lateinit var chLogo: android.widget.ImageView

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
        channelBar = findViewById(R.id.channel_bar)
        chName = findViewById(R.id.ch_name)
        chEpg = findViewById(R.id.ch_epg)
        chLogo = findViewById(R.id.ch_logo)

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
        chEpg.text = epgText.ifBlank { pc.shortQualityLabel() }
        if (logoUrl.isNotBlank() && !logoLoaded) {
            logoLoaded = true
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this).data(logoUrl).target(chLogo).crossfade(true).build()
            )
        }
        chLogo.visibility = if (logoUrl.isBlank()) View.GONE else View.VISIBLE
        channelBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideChannelBar)
        ui.postDelayed(hideChannelBar, 5_000)
        announce(title)
    }

    /** EPG do canal atual (preenchido quando o EPG estiver ligado — próximo passo). */
    private var epgText: String = ""

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

            // D-pad com controles escondidos: seek rápido de 10s
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controllerVisible) { seekBy(+10_000); true } else super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!controllerVisible) { seekBy(-10_000); true } else super.onKeyDown(keyCode, event)

            // OK mostra/esconde controles
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (!controllerVisible) { playerView.showController(); true } else super.onKeyDown(keyCode, event)

            // BACK: fecha controles antes de sair
            KeyEvent.KEYCODE_BACK -> if (controllerVisible) { playerView.hideController(); true } else super.onKeyDown(keyCode, event)

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun seekBy(deltaMs: Long) {
        if (isLive && !pc.player.isCurrentMediaItemSeekable) { showBadge("Ao vivo"); return }
        val p = pc.player
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(target)
        val sign = if (deltaMs >= 0) "+" else "−"
        showBadge("$sign${kotlin.math.abs(deltaMs) / 1000}s  •  ${fmtTime(target)}")
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    // ---------- Menu (MENU / amarelo) ----------
    private fun showMenu() {
        val video = pc.videoTracks()
        val audio = pc.audioTracks()
        val text = pc.textTracks()
        val options = ArrayList<Pair<String, () -> Unit>>()

        // Qualidade do canal ao vivo (streams HD/FHD/SD diferentes).
        if (variantUrls.size > 1) {
            variantUrls.indices.forEach { i ->
                val label = variantLabels.getOrElse(i) { "Opção ${i + 1}" }
                options += ("${mark(i == variantIdx)}Qualidade do canal: $label") to { switchVariant(i) }
            }
        }

        if (video.size > 1) {
            options += "Qualidade: Automática" to { pc.setAutoQuality(); showBadge("Qualidade automática", true) }
            video.sortedByDescending { it.label }.forEach { t ->
                options += ("${mark(t.isSelected)}Qualidade: ${t.label}") to { pc.selectTrack(t) }
            }
        }
        audio.forEach { t ->
            options += ("${mark(t.isSelected)}Áudio: ${t.label}") to { pc.selectTrack(t) }
        }
        options += "Legenda: Desligar" to { pc.disableSubtitles(); showBadge("Legenda desligada", true) }
        text.forEach { t ->
            options += ("${mark(t.isSelected)}Legenda: ${t.label}") to { pc.selectTrack(t) }
        }
        options += "Tamanho da legenda…" to { showSubtitleSizeMenu() }
        if (!isLive) options += "Velocidade…" to { showSpeedMenu() }
        options += "Aspecto: ${aspectModes[aspectIdx].second} (vermelho)" to { cycleAspect() }
        options += "Timer de desligar…" to { showSleepMenu() }
        options += "Picture-in-Picture (verde)" to { enterPip() }

        AlertDialog.Builder(this)
            .setTitle("Opções")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun showSubtitleSizeMenu() {
        val sizes = listOf("Pequena" to 16f, "Média" to 20f, "Grande" to 26f, "Enorme" to 34f)
        AlertDialog.Builder(this).setTitle("Tamanho da legenda")
            .setItems(sizes.map { it.first }.toTypedArray()) { _, i ->
                pc.setSubtitleSizeSp(playerView, sizes[i].second)
                showBadge("Legenda: ${sizes[i].first}", true)
            }.show()
    }

    private fun showSpeedMenu() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("Velocidade")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i ->
                pc.setSpeed(speeds[i]); showBadge("Velocidade ${speeds[i]}x", true)
            }.show()
    }

    private fun showSleepMenu() {
        val mins = listOf(0, 15, 30, 60, 90)
        AlertDialog.Builder(this).setTitle("Timer de desligar")
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

    override fun onStop() {
        super.onStop()
        if (settings.resumeEnabled) resume.save(resumeKey, pc.player.currentPosition, pc.player.duration)
        if (!inPip) pc.player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (settings.resumeEnabled) resume.save(resumeKey, pc.player.currentPosition, pc.player.duration)
        pc.release()
        if (directUrl.isBlank()) engine.stop()
    }

    companion object {
        const val EXTRA_DIRECT_URL = "direct_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"
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
