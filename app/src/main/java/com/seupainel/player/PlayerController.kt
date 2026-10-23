package com.seupainel.player

import android.content.Context
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

/**
 * Player Media3/ExoPlayer para IPTV/P2P em Android TV.
 *
 * Melhorias sobre o APK original:
 *  - SEM trava de SD; ABR agressivo; começa em HD (estimativa inicial alta).
 *  - Legendas externas por URL (.srt/.vtt/.ass) + estilo legível e ajustável.
 *  - Troca de faixa de áudio (dublado ↔ original) e de qualidade manual.
 *  - Modos LIVE (baixa latência) e VOD (buffer folgado) — buffer sob medida.
 *  - Áudio passthrough (AC3/EAC3/DTS) quando o device/TV suporta; senão SW.
 *  - Frame-rate matching e tunneling na TV (feito na Activity + aqui).
 *  - Estatísticas em tempo real para o HUD de diagnóstico.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val context: Context,
    private val mode: Mode = Mode.VOD,
) {

    enum class Mode { LIVE, VOD }

    data class ExternalSubtitle(
        val url: String,
        val label: String,
        val language: String,          // BCP-47, ex: "pt-BR", "en"
        val mime: String = MimeTypes.APPLICATION_SUBRIP, // srt
        val makeDefault: Boolean = false,
    )

    // ABR mais agressivo, afinado para P2P: sobe pra HD rápido e cai devagar.
    private val adaptiveFactory = AdaptiveTrackSelection.Factory(
        /* minDurationForQualityIncreaseMs   */ 6_000,
        /* maxDurationForQualityDecreaseMs   */ 30_000,
        /* minDurationToRetainAfterDiscardMs */ 25_000,
        /* bandwidthFraction                 */ 0.8f,
    )

    private val settings = AppSettings(context)

    // Estimativa inicial de banda vinda das Configurações (padrão 10 Mbps).
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setInitialBitrateEstimate(settings.connectionMbps.coerceAtLeast(1) * 1_000_000L)
        .build()

    private val trackSelector = DefaultTrackSelector(context, adaptiveFactory).apply {
        val audio = settings.audioLanguage.ifBlank { "pt" }
        val sub = settings.subtitleLanguage.ifBlank { "pt" }
        parameters = buildUponParameters()
            .setPreferredTextLanguages(sub, "pt-BR", "pt")
            .setPreferredAudioLanguages(audio, "pt-BR", "pt")
            .setExceedVideoConstraintsIfNecessary(true)
            .setExceedRendererCapabilitiesIfNecessary(true)
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowVideoNonSeamlessAdaptiveness(true)
            .setTunnelingEnabled(true)
            .setForceHighestSupportedBitrate(settings.forceMaxQuality)
            .build()
    }

    fun setForceMaxQuality(force: Boolean) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setForceHighestSupportedBitrate(force)
            .build()
    }

    // Factory HTTP sobre OkHttp com DNS-over-HTTPS (resolve hosts que o DNS da
    // TV bloqueia) + injeção de User-Agent/Referer por canal.
    private val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
        com.seupainel.player.net.Net.client
    )

    /** Define cabeçalhos HTTP para os próximos streams (antes de play()). */
    fun setHttpHeaders(userAgent: String?, referer: String?) {
        if (!userAgent.isNullOrBlank()) http.setUserAgent(userAgent)
        val headers = buildMap {
            if (!referer.isNullOrBlank()) put("Referer", referer)
        }
        if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
    }

    val player: ExoPlayer = run {

        // Buffer sob medida: LIVE prioriza baixa latência (zapping rápido);
        // VOD prioriza suavidade com buffer grande.
        val loadControl = when (mode) {
            Mode.LIVE -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 30_000, 1_000, 2_000)
                .setBackBuffer(30_000, true)   // rewind instantâneo em live
            Mode.VOD -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
        }
            .setPrioritizeTimeOverSizeThresholds(true) // começa a tocar antes
            .build()

        // Renderers afinados para TCL C6K (4K QLED, MediaTek, Google TV):
        // HARDWARE primeiro (HEVC/AV1 em HW + passthrough de áudio Atmos/DTS
        // para a TV/soundbar); FFmpeg entra só como fallback de codec raro.
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekForwardIncrementMs(60_000)
            .setSeekBackIncrementMs(60_000)
            .setHandleAudioBecomingNoisy(true) // pausa ao tirar HDMI/fone
            .setWakeMode(C.WAKE_MODE_NETWORK)  // mantém CPU/Wi-Fi durante o play
            .build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                // Pede ao device para casar a taxa de atualização da tela com o
                // conteúdo (remove judder de 24/25fps em TV de 60Hz).
                setVideoChangeFrameRateStrategy(
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                )
            }
    }

    fun attach(view: PlayerView) {
        view.player = player
        view.keepScreenOn = true
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        view.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            // Acessibilidade: se o usuário configurou legendas no sistema
            // (Configurações → Acessibilidade → Legendas), respeita o estilo e
            // o tamanho dele. Senão, usa um estilo legível para TV.
            val cm = context.getSystemService(Context.CAPTIONING_SERVICE)
                as? android.view.accessibility.CaptioningManager
            if (cm?.isEnabled == true) {
                setUserDefaultStyle()
                setUserDefaultTextSize()
            } else {
                setStyle(
                    CaptionStyleCompat(
                        Color.WHITE,
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK,
                        null,
                    )
                )
                setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, settings.subtitleSizeSp)
            }
        }
    }

    /** Rótulo curto para o indicador: "1080p HEVC • 6.2 Mbps". */
    fun shortQualityLabel(): String {
        val f = player.videoFormat ?: return "Carregando…"
        val res = when {
            f.height >= 2160 -> "4K"
            f.height >= 1080 -> "1080p"
            f.height >= 720 -> "720p"
            f.height > 0 -> "${f.height}p"
            else -> "?"
        }
        val codec = when (f.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            else -> f.sampleMimeType?.substringAfter("/")?.uppercase() ?: ""
        }
        val mbps = if (f.bitrate > 0) " • ${"%.1f".format(f.bitrate / 1_000_000.0)} Mbps" else ""
        val fps = if (f.frameRate > 0) " • ${f.frameRate.toInt()}fps" else ""
        return "$res $codec$mbps$fps".trim()
    }

    /** Tamanho da legenda em SP (ajuste pelo usuário). */
    fun setSubtitleSizeSp(view: PlayerView, sp: Float) {
        view.subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
    }

    fun play(
        streamUrl: String,
        subtitles: List<ExternalSubtitle> = emptyList(),
        startPositionMs: Long = 0,
        mimeType: String? = null,
    ) {
        val subConfigs = subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                .setMimeType(s.mime)
                .setLanguage(s.language)
                .setLabel(s.label)
                .setSelectionFlags(if (s.makeDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(streamUrl)
            .apply { mimeType?.let { setMimeType(it) } }
            .setSubtitleConfigurations(subConfigs)
            .build()

        player.setMediaItem(item, startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    // ---- faixas ----
    fun textTracks(): List<TrackInfo> = collect(C.TRACK_TYPE_TEXT)
    fun audioTracks(): List<TrackInfo> = collect(C.TRACK_TYPE_AUDIO)
    fun videoTracks(): List<TrackInfo> = collect(C.TRACK_TYPE_VIDEO)

    fun selectTrack(info: TrackInfo) {
        val group = info.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, info.trackIndex))
            .setTrackTypeDisabled(info.type, false)
            .build()
    }

    fun setAutoQuality() {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setForceHighestSupportedBitrate(false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun preferAudioLanguage(bcp47: String) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(bcp47)
            .build()
    }

    /** Velocidade de reprodução (0.5x–2x) — útil para VOD. */
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    // ---- estatísticas para o HUD ----
    data class Stats(
        val width: Int,
        val height: Int,
        val videoBitrate: Int,
        val codec: String?,
        val networkKbps: Long,
        val bufferedMs: Long,
        val droppedFrames: Int,
    )

    fun stats(): Stats {
        val f: Format? = player.videoFormat
        val dec = player.videoDecoderCounters
        return Stats(
            width = f?.width ?: 0,
            height = f?.height ?: 0,
            videoBitrate = f?.bitrate ?: 0,
            codec = f?.codecs ?: f?.sampleMimeType,
            networkKbps = bandwidthMeter.bitrateEstimate / 1000,
            bufferedMs = player.totalBufferedDuration,
            droppedFrames = dec?.droppedBufferCount ?: 0,
        )
    }

    fun release() = player.release()

    data class TrackInfo(
        /** C.TRACK_TYPE_VIDEO / AUDIO / TEXT */
        val type: Int,
        val label: String,
        val language: String?,
        val trackIndex: Int,
        val isSelected: Boolean,
        val group: Tracks.Group?,
    )

    private fun collect(type: Int): List<TrackInfo> {
        val out = ArrayList<TrackInfo>()
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val lang = f.language
                val label = when (type) {
                    C.TRACK_TYPE_VIDEO -> videoLabel(f.height, f.bitrate)
                    else -> f.label ?: lang?.let { langDisplay(it) } ?: "Faixa ${i + 1}"
                }
                out.add(
                    TrackInfo(type, label, lang, i, group.isTrackSelected(i), group)
                )
            }
        }
        return out
    }

    private fun langDisplay(code: String): String = try {
        java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
    } catch (_: Exception) { code }

    companion object {
        /** Modos de aspecto (RESIZE_MODE do PlayerView, rótulo). Índice usado nas Configurações. */
        val ASPECTS = listOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "Preencher",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "16:9",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "4:3",
        )
    }

    private fun videoLabel(height: Int, bitrate: Int): String {
        val res = when {
            height <= 0 -> "Auto"
            height >= 2160 -> "4K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            else -> "${height}p"
        }
        val mbps = if (bitrate > 0) " (${"%.1f".format(bitrate / 1_000_000.0)} Mbps)" else ""
        return res + mbps
    }
}
