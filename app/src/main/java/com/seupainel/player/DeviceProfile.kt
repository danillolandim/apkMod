package com.seupainel.player

import android.app.ActivityManager
import android.content.Context

/**
 * Limites de memória e de buffer conforme o aparelho. Os valores "cheios" foram afinados para TV 4K recente; em
 * aparelho com pouca memória (TV box antiga) eles apertam o sistema, que então fecha o app em segundo plano ou
 * engasga. A decisão ([isLow], [limits]) não usa Android e tem testes JVM.
 */
object DeviceProfile {

    /** Até 2 GB de RAM (o aparelho informa um pouco menos que o nominal: 2 GB aparece como ~1,9 GB). */
    const val LOW_RAM_BYTES = 2_200L * 1024 * 1024

    class Limits(
        val low: Boolean,
        /** Fração da memória do app usada pelo cache de imagens. */
        val imageMemoryPercent: Double,
        val posterDiskBytes: Long,
        val liveMinBufferMs: Int,
        val liveMaxBufferMs: Int,
        /** Vídeo já visto que fica guardado no ao vivo. */
        val liveBackBufferMs: Int,
        val vodMinBufferMs: Int,
        val vodMaxBufferMs: Int,
        /** Deixar o decodificador do próximo episódio pronto: usa um segundo decodificador de vídeo. */
        val prewarmNextVideo: Boolean,
    )

    /** [totalRamBytes] <= 0 = o aparelho não informou: vale só o aviso do sistema. */
    fun isLow(lowRamFlag: Boolean, totalRamBytes: Long): Boolean =
        lowRamFlag || totalRamBytes in 1..LOW_RAM_BYTES

    fun limits(low: Boolean): Limits = if (low) Limits(
        low = true,
        imageMemoryPercent = 0.15,
        posterDiskBytes = 100L * 1024 * 1024,
        liveMinBufferMs = 10_000, liveMaxBufferMs = 30_000, liveBackBufferMs = 10_000,
        vodMinBufferMs = 15_000, vodMaxBufferMs = 40_000,
        prewarmNextVideo = false,
    ) else Limits(
        low = false,
        imageMemoryPercent = 0.30,
        posterDiskBytes = 300L * 1024 * 1024,
        liveMinBufferMs = 10_000, liveMaxBufferMs = 50_000, liveBackBufferMs = 30_000,
        vodMinBufferMs = 25_000, vodMaxBufferMs = 90_000,
        prewarmNextVideo = true,
    )

    @Volatile private var cached: Limits? = null

    fun of(context: Context): Limits = cached ?: run {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val total = runCatching { ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
        limits(isLow(am?.isLowRamDevice == true, total)).also { cached = it }
    }
}
