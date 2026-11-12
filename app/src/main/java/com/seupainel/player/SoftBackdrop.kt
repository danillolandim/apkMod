package com.seupainel.player

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/** Desfoque de caixa sobre pixels ARGB (puro, testado em JVM). Em imagem pequena é barato e dispensa biblioteca. */
object BoxBlur {

    /** Média dos vizinhos num quadrado de lado 2*[radius]+1, [passes] vezes, com as bordas repetidas. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        if (radius <= 0 || passes <= 0 || w <= 0 || h <= 0 || px.size != w * h) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            average(px, tmp, w, h, radius, horizontal = true)
            average(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun average(from: IntArray, to: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val count = 2 * r + 1
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val sx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                val sy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                val p = from[sy * w + sx]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            to[y * w + x] = ((a / count) shl 24) or ((rr / count) shl 16) or ((g / count) shl 8) or (b / count)
        }
    }
}

/**
 * Fundo do detalhe quando não há arte horizontal: o próprio cartaz, reduzido e muito desfocado. O Coil pede a imagem
 * já pequena, então o desfoque roda em poucos milhares de pixels.
 */
object SoftBackdrop : Transformation {

    const val WIDTH = 64
    const val HEIGHT = 96
    private const val RADIUS = 4
    private const val PASSES = 3

    override val cacheKey: String = "soft_backdrop_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val small = Bitmap.createScaledBitmap(input, WIDTH, HEIGHT, true)
        val px = IntArray(WIDTH * HEIGHT)
        small.getPixels(px, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        BoxBlur.blur(px, WIDTH, HEIGHT, RADIUS, PASSES)
        return Bitmap.createBitmap(px, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }
}
