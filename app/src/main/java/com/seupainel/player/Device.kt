package com.seupainel.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * TV ou celular? O mesmo APK roda nos dois, e tudo o que existe só para o toque fica atrás de [isTv]: na TV nada
 * muda. Na dúvida vale TV (é o lado já testado): um aparelho sem tela de toque nunca é tratado como celular.
 */
object Device {

    /**
     * [televisionMode]: o sistema se declara televisão (UiModeManager). [leanback] e [televisionFeature]: a TV traz
     * o recurso (Android TV / TV box). Sem [touchscreen] não há como ser celular nem tablet. A decisão não usa Android
     * e tem testes JVM.
     */
    fun classify(televisionMode: Boolean, leanback: Boolean, televisionFeature: Boolean, touchscreen: Boolean): Boolean =
        televisionMode || leanback || televisionFeature || !touchscreen

    @Volatile private var cachedTv: Boolean? = null

    /** Barato: lê o sistema uma vez e guarda. Pode ser chamado de qualquer tela, a qualquer momento. */
    fun isTv(context: Context): Boolean = cachedTv ?: detect(context.applicationContext).also { cachedTv = it }

    private fun detect(context: Context): Boolean {
        val pm = context.packageManager
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return classify(
            televisionMode = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION,
            leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            // PackageManager.FEATURE_TELEVISION está obsoleta, mas TV box mais antiga só declara esta
            televisionFeature = pm.hasSystemFeature("android.hardware.type.television"),
            touchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        )
    }
}
