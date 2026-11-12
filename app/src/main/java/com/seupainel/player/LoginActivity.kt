package com.seupainel.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.ServerInput
import com.seupainel.player.playlist.XtreamApi
import com.seupainel.player.playlist.XtreamCatalog
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Primeiro uso: a pessoa digita só usuário e senha; o servidor (DNS) já vem de fábrica
 * ([BuildConfig.DEFAULT_SERVER], injetado na compilação). Servidor e servidor alternativo ficam em "Opções avançadas";
 * a chave do TMDB fica só nas Configurações. Valida a conta no servidor antes de salvar.
 */
class LoginActivity : FragmentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var server: EditText
    private lateinit var alt: EditText
    private lateinit var status: TextView
    private lateinit var enter: Button
    private lateinit var advanced: View
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        user = findViewById(R.id.login_user)
        pass = findViewById(R.id.login_pass)
        server = findViewById(R.id.login_server)
        alt = findViewById(R.id.login_alt)
        status = findViewById(R.id.login_status)
        enter = findViewById(R.id.login_enter)
        advanced = findViewById(R.id.login_advanced)
        toggle = findViewById(R.id.login_toggle_advanced)

        // Já tem conta salva (editando): preenche. Senão, o servidor de fábrica.
        val current = XtreamApi.accountFrom(PlaylistStore(this).playlistUrl)
        user.setText(current?.user.orEmpty())
        pass.setText(current?.pass.orEmpty())
        server.setText(current?.base ?: BuildConfig.DEFAULT_SERVER)
        // Sem servidor de fábrica (build local/fork) o campo é obrigatório: as opções já abrem visíveis.
        setAdvanced(BuildConfig.DEFAULT_SERVER.isBlank())

        toggle.setOnClickListener { setAdvanced(advanced.visibility != View.VISIBLE) }
        findViewById<CheckBox>(R.id.login_show_pass).setOnCheckedChangeListener { _, on ->
            pass.transformationMethod = if (on) null else PasswordTransformationMethod.getInstance()
            pass.setSelection(pass.text.length)
        }
        findViewById<Button>(R.id.login_m3u).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        enter.setOnClickListener { attempt() }
        pass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attempt(); true } else false
        }
        // Colou a URL completa do provedor no servidor? Separa usuário e senha sozinho.
        server.setOnFocusChangeListener { _, focused -> if (!focused) fillFromPastedUrl() }
        user.requestFocus()
    }

    private fun setAdvanced(show: Boolean) {
        advanced.visibility = if (show) View.VISIBLE else View.GONE
        toggle.text = if (show) "Ocultar opções avançadas" else "Opções avançadas"
    }

    private fun fillFromPastedUrl() {
        val parsed = ServerInput.parse(server.text.toString()) ?: return
        if (parsed.user != null && user.text.isBlank()) user.setText(parsed.user)
        if (parsed.pass != null && pass.text.isBlank()) pass.setText(parsed.pass)
        if (parsed.user != null || parsed.pass != null) server.setText(parsed.base)
    }

    private fun show(text: String, color: Int) {
        status.setTextColor(color)
        status.text = text
    }
    private fun info(text: String) = show(text, color(R.color.lf_text_2))
    private fun ok(text: String) = show(text, color(R.color.lf_accent))
    private fun fail(text: String) = show(text, Color.parseColor("#F28B82"))
    private fun color(id: Int) = ResourcesCompat.getColor(resources, id, theme)

    private fun attempt() {
        val typed = server.text.toString().trim().ifBlank { BuildConfig.DEFAULT_SERVER }
        val primary = ServerInput.parse(typed)
        if (primary == null) {
            fail("Digite o endereço do servidor (DNS). Exemplo: http://servidor.com:8080")
            setAdvanced(true); server.requestFocus(); return
        }
        val u = user.text.toString().trim().ifBlank { primary.user.orEmpty() }
        val p = pass.text.toString().ifBlank { primary.pass.orEmpty() }
        if (u.isBlank()) { fail("Digite o usuário."); user.requestFocus(); return }
        if (p.isBlank()) { fail("Digite a senha."); pass.requestFocus(); return }
        val altParsed = ServerInput.parse(alt.text.toString())?.takeIf { it.base != primary.base }

        enter.isEnabled = false
        info("Testando a conexão…")
        val ua = PlaylistStore(this).userAgent
        thread(name = "login") {
            var chosen = primary.base
            var result = XtreamApi.login(primary.base, u, p, ua)
            if (result is XtreamApi.LoginResult.Fail && altParsed != null) {
                val r2 = XtreamApi.login(altParsed.base, u, p, ua)
                if (r2 is XtreamApi.LoginResult.Ok) { chosen = altParsed.base; result = r2 }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                enter.isEnabled = true
                when (val r = result) {
                    is XtreamApi.LoginResult.Fail -> fail(r.message)
                    is XtreamApi.LoginResult.Ok -> onSuccess(chosen, u, p, r, usedAlt = chosen != primary.base)
                }
            }
        }
    }

    private fun onSuccess(base: String, u: String, p: String, r: XtreamApi.LoginResult.Ok, usedAlt: Boolean) {
        val store = PlaylistStore(this)
        val newUrl = "$base/get.php?username=${URLEncoder.encode(u, "UTF-8")}" +
            "&password=${URLEncoder.encode(p, "UTF-8")}&type=m3u_plus&output=m3u8"
        if (newUrl != store.playlistUrl) {
            // Conta nova: o que estava em cache era de outra conta.
            PlaylistHolder.clear()
            store.clearCache()
            XtreamCatalog.clearCaches(applicationContext, store)
        }
        store.playlistUrl = newUrl
        store.configUrl = null
        store.lastUrl = newUrl
        // Formato padrão das URLs ao vivo: já dá para abrir sem a lista M3U desde a próxima vez.
        if (store.liveUrlTemplate == null) store.liveUrlTemplate = "$base/live/$u/$p/{id}.ts"

        val conns = if (r.active != null && r.max != null) " · conexões ${r.active}/${r.max}" else ""
        val plain = Regex("[A-Za-z0-9._-]+")
        val warn = if (!plain.matches(u) || !plain.matches(p))
            "\nAtenção: caracteres especiais no usuário ou na senha podem dar problema em alguns vídeos." else ""
        val header = "Conectado ✓  Conta ${r.status} · vence ${r.expires ?: "sem data"}$conns" +
            (if (usedAlt) " · servidor alternativo" else "") + warn
        ok("$header\nCarregando seu catálogo…")
        enter.isEnabled = false
        val started = System.currentTimeMillis()
        // Teto: com a rede muito lenta entra assim mesmo (o catálogo termina de carregar depois).
        ui.postDelayed({ goHome() }, 45_000L)
        thread(name = "login-warm") {
            runCatching { XtreamCatalog.warm(applicationContext, store, emptyList()) }
            // Dá tempo de ler a mensagem (mais tempo se houver aviso).
            val minShow = if (warn.isEmpty()) 1_500L else 3_500L
            val wait = (minShow - (System.currentTimeMillis() - started)).coerceAtLeast(0L)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                ok(header + "\n" + XtreamCatalog.lastStatus.ifBlank { "Catálogo pronto." })
                ui.postDelayed({ goHome() }, wait + 600L)
            }
        }
    }

    private var left = false
    private fun goHome() {
        if (left || isFinishing) return
        left = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
    }
}
