package com.seupainel.player

import kotlin.math.max

/**
 * Lógica pura (sem Android) do "scrub" do filme/série: as setas movem um alvo na hora e o pulo de verdade só acontece
 * depois, quando o usuário para (a tela confirma). Segurar a tecla acelera o passo.
 *
 * Duração desconhecida (C.TIME_UNSET, 0 ou negativa) limita o alvo só em zero.
 */
class VodScrub(private val baseStepMs: Long) {

    private var origin = 0L
    private var durationMs = UNKNOWN
    private var active = false

    var targetMs = 0L
        private set

    val isActive: Boolean get() = active

    fun begin(positionMs: Long, durationMs: Long) {
        this.durationMs = if (durationMs > 0) durationMs else UNKNOWN
        origin = clamp(positionMs)
        targetMs = origin
        active = true
    }

    /** [direction]: só o sinal vale (+ avança, − volta). [repeatCount]: repetições da tecla segurada (0 = primeiro toque). */
    fun step(direction: Int, repeatCount: Int): Long = jump(direction.coerceIn(-1, 1) * stepFor(baseStepMs, repeatCount))

    /** Pulo fixo (teclas de avançar/voltar rápido): soma [deltaMs] ao alvo. */
    fun jump(deltaMs: Long): Long {
        if (active) targetMs = clamp(targetMs + deltaMs)
        return targetMs
    }

    /** Arrastar com o dedo (celular): leva o alvo direto a [ms]. */
    fun moveTo(ms: Long): Long {
        if (active) targetMs = clamp(ms)
        return targetMs
    }

    /** Confirma: devolve o alvo e encerra. Null se não havia scrub. */
    fun commit(): Long? {
        if (!active) return null
        active = false
        return targetMs
    }

    /** Desiste: devolve a posição de quando começou e encerra. Null se não havia scrub. */
    fun cancel(): Long? {
        if (!active) return null
        active = false
        targetMs = origin
        return origin
    }

    private fun clamp(ms: Long): Long = if (durationMs == UNKNOWN) max(0L, ms) else ms.coerceIn(0L, durationMs)

    companion object {
        private const val UNKNOWN = -1L
        private const val MEDIUM_AFTER = 5     // repetições a partir das quais o passo sobe para 30 s
        private const val FAST_AFTER = 15      // e para 60 s
        private const val MEDIUM_STEP_MS = 30_000L
        private const val FAST_STEP_MS = 60_000L

        /** Passo da tecla segurada: o das Configurações no começo, depois 30 s e 60 s (nunca menos que o das Configurações). */
        fun stepFor(baseMs: Long, repeatCount: Int): Long = when {
            repeatCount >= FAST_AFTER -> max(baseMs, FAST_STEP_MS)
            repeatCount >= MEDIUM_AFTER -> max(baseMs, MEDIUM_STEP_MS)
            else -> baseMs
        }

        /** "5:03", "32:10", "1:02:15". Descarta a fração de segundo; negativo vira zero. */
        fun fmt(ms: Long): String = clock(max(0L, ms) / 1000)

        /** "-1:02:15": quanto falta. Arredonda para cima, para decorrido + restante fechar a duração. Vazio sem duração. */
        fun fmtRemaining(positionMs: Long, durationMs: Long): String {
            if (durationMs <= 0) return ""
            val left = max(0L, durationMs - positionMs)
            return "-" + clock((left + 999) / 1000)
        }

        // Sem String.format: o separador e os dígitos não podem mudar com o idioma do aparelho.
        private fun clock(totalSec: Long): String {
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            val ss = s.toString().padStart(2, '0')
            return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
        }
    }
}
