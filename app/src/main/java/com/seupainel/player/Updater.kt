package com.seupainel.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Atualização dentro do app (sem Downloader).
 *
 * Compara o versionCode local com o version.txt publicado na release "latest"
 * do GitHub. Se houver versão nova, baixa o APK e abre o instalador do Android.
 *
 * O número da versão sobe a cada build no CI (GITHUB_RUN_NUMBER), então toda vez
 * que o app for recompilado o aparelho detecta e se oferece para atualizar.
 */
object Updater {

    private const val BASE = "https://github.com/danillolandim/apkMod/releases/download/latest"
    private const val VERSION_URL = "$BASE/version.txt"
    private const val CHANGELOG_URL = "$BASE/changelog.txt"
    private const val APK_URL = "$BASE/player-mod.apk"

    /**
     * @param silent se true, não avisa quando já está atualizado (uso no auto-check).
     */
    fun check(context: Context, silent: Boolean) {
        thread(name = "update-check") {
            try {
                val remote = fetch(VERSION_URL).trim().toIntOrNull() ?: return@thread
                val local = BuildConfig.VERSION_CODE
                if (remote > local) {
                    val changelog = runCatching { fetch(CHANGELOG_URL) }.getOrNull()
                    runOnMain(context) { promptUpdate(context, local, remote, changelog) }
                } else if (!silent) {
                    runOnMain(context) { toast(context, "Você já está na versão mais recente ($local).") }
                }
            } catch (e: Exception) {
                if (!silent) runOnMain(context) { toast(context, "Não foi possível verificar atualização: ${e.message}") }
            }
        }
    }

    private fun promptUpdate(context: Context, local: Int, remote: Int, changelog: String?) {
        // Mostra só a seção "Esta versão" do changelog, se houver.
        val whatsNew = changelog
            ?.substringAfter("## Esta versão", "")
            ?.substringBefore("## ", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val msg = buildString {
            append("Nova versão disponível (você tem a $local).")
            if (whatsNew != null) { append("\n\nNovidades:\n"); append(whatsNew) }
            append("\n\nBaixar e instalar agora?")
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Atualização disponível")
            .setMessage(msg)
            .setPositiveButton("Atualizar") { _, _ -> download(context) }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun download(context: Context) {
        val progress = Toast.makeText(context, "Baixando atualização…", Toast.LENGTH_LONG)
        progress.show()
        thread(name = "update-download") {
            try {
                val apk = File(context.cacheDir, "update.apk")
                URL(APK_URL).openStream().use { input ->
                    apk.outputStream().use { out -> input.copyTo(out) }
                }
                runOnMain(context) { install(context, apk) }
            } catch (e: Exception) {
                runOnMain(context) { toast(context, "Falha no download: ${e.message}") }
            }
        }
    }

    private fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            toast(context, "Abra as permissões para instalar apps: ${e.message}")
        }
    }

    private fun fetch(urlStr: String): String {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private fun runOnMain(context: Context, block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // só age se o contexto ainda é uma Activity viva
            if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) return@post
            block()
        }
    }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
