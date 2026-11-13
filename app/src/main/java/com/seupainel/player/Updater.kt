package com.seupainel.player

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Atualização dentro do app (sem Downloader).
 *
 * Compara o versionCode local com o version.txt publicado na release "latest"
 * do GitHub. Se houver versão nova, avisa (instalar, depois ou pular esta versão),
 * baixa o APK mostrando o progresso e abre o instalador do Android.
 *
 * A verificação automática roda toda vez que o app VOLTA PARA A FRENTE, não só quando o processo nasce: na
 * Google TV o app costuma ficar vivo em segundo plano e reabrir na tela onde estava, sem passar pela abertura.
 * O aviso é mostrado na tela que estiver na frente quando a resposta chega (nunca por cima do vídeo).
 */
object Updater {

    private const val BASE = "https://github.com/danillolandim/apkMod/releases/download/latest"
    private const val VERSION_URL = "$BASE/version.txt"
    private const val CHANGELOG_URL = "$BASE/changelog.txt"
    private const val APK_URL = "$BASE/player-mod.apk"

    private class Offer(val local: Int, val remote: Int, val changelog: String?)

    private val main = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean()
    private val downloading = AtomicBoolean()

    // Tudo abaixo só é tocado na thread principal.
    private var started = 0
    private var current = WeakReference<Activity>(null)
    private var pending: Offer? = null
    private var dialog: AlertDialog? = null
    private var shown: Offer? = null
    private var progress: AlertDialog? = null
    private var lastAuto = 0L

    /** Chamado uma vez no [App]: acompanha a tela da frente e verifica quando o app volta do segundo plano. */
    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0) autoCheck(a.applicationContext)
            }

            override fun onActivityStopped(a: Activity) {
                if (started > 0) started--
            }

            override fun onActivityResumed(a: Activity) {
                current = WeakReference(a)
                showPending()
            }

            override fun onActivityPaused(a: Activity) {
                if (current.get() === a) current = WeakReference(null)
            }

            override fun onActivityDestroyed(a: Activity) {
                // Diálogo preso a uma tela que morreu: a oferta volta para a fila e aparece na próxima tela.
                dialog?.takeIf { it.ownerActivity === a }?.let { d ->
                    pending = shown
                    runCatching { d.dismiss() }
                    dialog = null
                }
                progress?.takeIf { it.ownerActivity === a }?.let { d ->
                    runCatching { d.dismiss() }
                    progress = null
                }
            }

            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) {}
        })
    }

    private fun autoCheck(app: Context) {
        if (!AppSettings(app).autoUpdate) return
        val now = SystemClock.elapsedRealtime()
        if (!UpdateLogic.dueForAutoCheck(now, lastAuto)) return
        lastAuto = now
        run(app, manual = false)
    }

    /** Verificação pedida pelo usuário (Configurações): sempre responde algo e ignora a versão pulada. */
    fun check(context: Context, silent: Boolean) = run(context.applicationContext, manual = !silent)

    private fun run(app: Context, manual: Boolean) {
        if (!checking.compareAndSet(false, true)) {
            if (manual) toast(app, "Já estou verificando…")
            return
        }
        thread(name = "update-check") {
            try {
                val remote = UpdateLogic.parseVersion(fetch(VERSION_URL))
                val local = BuildConfig.VERSION_CODE
                when {
                    remote == null ->
                        if (manual) main.post { toast(app, "Não foi possível ler a versão publicada. Tente de novo.") }
                    UpdateLogic.shouldPrompt(remote, local, AppSettings(app).skippedUpdate, manual) -> {
                        val changelog = runCatching { fetch(CHANGELOG_URL) }.getOrNull()
                        main.post { pending = Offer(local, remote, changelog); showPending() }
                    }
                    manual -> main.post { toast(app, "Você já está na versão mais recente ($local).") }
                }
            } catch (e: Exception) {
                if (manual) main.post { toast(app, "Não foi possível verificar atualização: ${e.message}") }
            } finally {
                checking.set(false)
            }
        }
    }

    /** Mostra a oferta guardada assim que houver uma tela adequada na frente. */
    private fun showPending() {
        val offer = pending ?: return
        if (dialog?.isShowing == true || downloading.get()) return
        val a = current.get() ?: return
        // Abertura some em instantes e o player não deve ser coberto: espera a próxima tela.
        if (a.isFinishing || a.isDestroyed || a is SplashActivity || a is PlayerActivity) return
        pending = null
        shown = offer
        val app = a.applicationContext
        val msg = buildString {
            append("Versão ${offer.remote} disponível (você tem a ${offer.local}).")
            UpdateLogic.whatsNew(offer.changelog)?.let { append("\n\nNovidades:\n"); append(it) }
        }
        dialog = AlertDialog.Builder(a)
            .setTitle("Atualização disponível")
            .setMessage(msg)
            .setPositiveButton("Instalar") { _, _ -> download(a) }
            .setNegativeButton("Depois", null)
            .setNeutralButton("Pular esta versão") { _, _ ->
                AppSettings(app).skippedUpdate = offer.remote
                toast(app, "Versão ${offer.remote} pulada. Aviso de novo quando sair a próxima.")
            }
            .setOnDismissListener { dialog = null; shown = null }
            .create()
            .also { it.setOwnerActivity(a); it.show() }
    }

    private fun download(a: Activity) {
        if (!downloading.compareAndSet(false, true)) return
        val app = a.applicationContext
        // Janela que fica na tela até o fim: um aviso que some dava a impressão de que nada tinha acontecido.
        val box = AlertDialog.Builder(a)
            .setTitle("Baixando atualização")
            .setMessage("Conectando…")
            .setCancelable(false)
            .create()
        box.setOwnerActivity(a)
        runCatching { box.show() }
        progress = box
        thread(name = "update-download") {
            var error: String? = null
            val apk = File(app.cacheDir, "update.apk")
            val part = File(app.cacheDir, "update.apk.part")
            try {
                val c = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true; useCaches = false
                }
                try {
                    val total = c.contentLength.toLong()
                    var done = 0L
                    var shown = -1
                    c.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = UpdateLogic.percent(done, total) ?: -1
                                val mb = (done / (1024 * 1024)).toInt()
                                val mark = if (pct >= 0) pct else mb
                                if (mark != shown) {
                                    shown = mark
                                    val text = if (pct >= 0) "$pct%" else "$mb MB"
                                    main.post { progress?.takeIf { it.isShowing }?.setMessage("Baixado: $text") }
                                }
                            }
                        }
                    }
                    if (!UpdateLogic.complete(done, total)) error = "o download veio incompleto"
                    else if (!part.renameTo(apk)) { apk.delete(); if (!part.renameTo(apk)) error = "não consegui gravar o arquivo" }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            if (error != null) part.delete()
            main.post {
                downloading.set(false)
                progress?.let { d -> runCatching { d.dismiss() } }
                progress = null
                if (error != null) toast(app, "Falha no download: $error") else install(app, apk)
            }
        }
    }

    private fun install(app: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            toast(app, "Abra as permissões para instalar apps: ${e.message}")
        }
    }

    private fun fetch(urlStr: String): String {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            // Sem cópia intermediária: logo depois de publicar, uma resposta velha esconderia a versão nova.
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
