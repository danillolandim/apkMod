package com.seupainel.player

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity

/**
 * Botão "Transmitir" (só no celular): abre o painel de espelhamento de tela do próprio aparelho, de onde a pessoa
 * escolhe a TV (Samsung, LG, Chromecast…). O app não fala com a TV; quem espelha é o Android. O painel muda de um
 * fabricante para outro, então há uma lista de telas a tentar. Qual tentar ([candidates], [firstAvailable]) não usa
 * Android e tem testes JVM.
 */
object CastScreen {

    /** Uma tela de configurações que pode abrir o espelhamento, identificada pela ação do Intent. */
    data class IntentSpec(val action: String)

    /**
     * Na ordem de tentativa, da mais direta para a mais genérica: o painel Transmitir (Samsung e Xiaomi abrem o
     * equivalente deles), o Wi-Fi Display de aparelhos mais antigos, o Smart View da Samsung e, por último, as redes
     * sem fio, de onde ainda se chega ao Transmitir.
     */
    fun candidates(): List<IntentSpec> = listOf(
        IntentSpec("android.settings.CAST_SETTINGS"),
        IntentSpec("android.settings.WIFI_DISPLAY_SETTINGS"),
        IntentSpec("com.samsung.android.smartmirroring.CAST_SETTINGS"),
        IntentSpec("android.settings.WIRELESS_SETTINGS"),
    )

    /** A primeira da lista que o aparelho sabe abrir, ou null se nenhuma. */
    fun firstAvailable(candidates: List<IntentSpec>, canResolve: (IntentSpec) -> Boolean): IntentSpec? =
        candidates.firstOrNull(canResolve)

    /**
     * Abre o painel de espelhamento, com a explicação na primeira vez. [onOpened] é chamado quando o painel de fato
     * abriu (o player usa para retomar a imagem ao voltar). Sem painel, uma caixa ensina o caminho pelos atalhos.
     */
    fun show(activity: FragmentActivity, onOpened: () -> Unit = {}) {
        val settings = AppSettings(activity)
        fun go() { if (open(activity)) onOpened() else explainManual(activity) }
        if (settings.castHintSeen) { go(); return }
        AlertDialog.Builder(activity, R.style.Theme_Player_Dialog)
            .setTitle("Transmitir para a TV")
            .setMessage("A TV e o celular precisam estar no mesmo Wi-Fi.\n\nEscolha a TV na próxima tela e volte para o app.")
            .setPositiveButton("Continuar") { _, _ -> settings.castHintSeen = true; go() }
            .setNegativeButton("Agora não", null)
            .show()
    }

    /** true = alguma tela abriu. Se a escolhida falhar ao abrir, tenta a seguinte da lista. */
    private fun open(activity: FragmentActivity): Boolean {
        var left = candidates()
        while (true) {
            val spec = firstAvailable(left) { canOpen(activity, intentOf(it)) } ?: return false
            try {
                activity.startActivity(intentOf(spec))
                return true
            } catch (_: ActivityNotFoundException) {
                left = left - spec
            } catch (_: SecurityException) {
                left = left - spec
            }
        }
    }

    // NO_USER_ACTION: abrir o painel não pode contar como "sair do app" (o player entraria em picture-in-picture)
    private fun intentOf(spec: IntentSpec) = Intent(spec.action).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    private fun canOpen(activity: FragmentActivity, intent: Intent): Boolean =
        intent.resolveActivity(activity.packageManager) != null

    private fun explainManual(activity: FragmentActivity) {
        AlertDialog.Builder(activity, R.style.Theme_Player_Dialog)
            .setTitle("Transmitir para a TV")
            .setMessage(
                "Não encontrei a tela de transmissão neste aparelho.\n\n" +
                    "Deslize o dedo do alto da tela para baixo, para abrir os atalhos rápidos, e toque em " +
                    "Transmitir, Smart View ou Espelhar tela. A TV e o celular precisam estar no mesmo Wi-Fi."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }
}
