package com.seupainel.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max

/**
 * Linha de progresso do filme/série: trilho, parte já carregada, progresso e bolinha. Não é focável (na TV quem
 * move o alvo é a Activity, pelas teclas). No celular [touchEnabled] liga o arrastar com o dedo.
 *
 * Durante o scrub o trilho engrossa, a bolinha cresce e um balão com o tempo do alvo aparece acima dela. O balão é
 * desenhado fora dos limites da view (o pai precisa de clipChildren=false), então a view não precisa ser alta.
 */
class VodTimeBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onScrubStart(fraction: Float)
        fun onScrubMove(fraction: Float)
        fun onScrubStop(fraction: Float, canceled: Boolean)
    }

    var listener: Listener? = null
    var touchEnabled = false

    var scrubbing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var durationMs = 0L
    private var positionMs = 0L
    private var bufferedMs = 0L

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val trackH = dp(4f)
    private val trackScrubH = dp(6f)
    private val thumbR = dp(7f)
    private val thumbScrubR = dp(9f)
    private val inset = thumbScrubR   // a bolinha grande não pode ser cortada nas pontas

    private val trackPaint = fill(R.color.vod_track)
    private val bufferPaint = fill(R.color.vod_buffer)
    private val progressPaint = fill(R.color.lf_primary)
    private val thumbPaint = fill(R.color.lf_accent)
    private val balloonPaint = fill(R.color.lf_surface_hi)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lf_text)
        textSize = sp(16f)
        typeface = ResourcesCompat.getFont(context, R.font.rajdhani_semibold)
    }
    private val rect = RectF()

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ContextCompat.getColor(context, color) }

    /** [durationMs] desconhecida (C.TIME_UNSET é negativo) desenha só o trilho. */
    fun setProgress(positionMs: Long, bufferedMs: Long, durationMs: Long) {
        val dur = max(0L, durationMs)
        if (this.positionMs == positionMs && this.bufferedMs == bufferedMs && this.durationMs == dur) return
        this.positionMs = positionMs
        this.bufferedMs = bufferedMs
        this.durationMs = dur
        invalidate()
    }

    /** Posição da bolinha, no sistema de coordenadas desta view. */
    fun thumbCenterX(): Float = inset + trackWidth() * fractionOf(positionMs)

    private fun trackWidth() = max(0f, width - 2 * inset)

    private fun fractionOf(ms: Long): Float =
        if (durationMs <= 0) 0f else (ms.toFloat() / durationMs).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val h = if (scrubbing) trackScrubH else trackH
        val r = h / 2f
        val right = inset + trackWidth()

        rect.set(inset, cy - r, right, cy + r)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val bufferX = inset + trackWidth() * fractionOf(bufferedMs)
        if (bufferX > inset) {
            rect.set(inset, cy - r, bufferX, cy + r)
            canvas.drawRoundRect(rect, r, r, bufferPaint)
        }

        val thumbX = thumbCenterX()
        if (thumbX > inset) {
            rect.set(inset, cy - r, thumbX, cy + r)
            canvas.drawRoundRect(rect, r, r, progressPaint)
        }
        canvas.drawCircle(thumbX, cy, if (scrubbing) thumbScrubR else thumbR, thumbPaint)

        if (scrubbing && durationMs > 0) drawBalloon(canvas, thumbX, cy)
    }

    private fun drawBalloon(canvas: Canvas, thumbX: Float, cy: Float) {
        val text = VodScrub.fmt(positionMs)
        val padX = dp(10f)
        val padY = dp(5f)
        val fm = textPaint.fontMetrics
        val w = textPaint.measureText(text) + 2 * padX
        val h = fm.descent - fm.ascent + 2 * padY
        val x = (thumbX - w / 2).coerceIn(0f, max(0f, width - w))
        val y = cy - thumbScrubR - dp(8f) - h
        rect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), balloonPaint)
        canvas.drawText(text, x + padX, y + padY - fm.ascent, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled || durationMs <= 0) return false
        val f = fractionAt(event.x)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onScrubStart(f)
            }
            MotionEvent.ACTION_MOVE -> listener?.onScrubMove(f)
            MotionEvent.ACTION_UP -> listener?.onScrubStop(f, false)
            MotionEvent.ACTION_CANCEL -> listener?.onScrubStop(f, true)
        }
        return true
    }

    private fun fractionAt(x: Float): Float {
        val w = trackWidth()
        return if (w <= 0f) 0f else ((x - inset) / w).coerceIn(0f, 1f)
    }
}
