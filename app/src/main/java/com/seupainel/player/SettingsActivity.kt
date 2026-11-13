package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.playlist.ChannelListActivity
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.XtreamApi
import com.seupainel.player.playlist.XtreamCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Configurações em duas colunas: as seções à esquerda e, à direita, as linhas da seção em foco. Mover o foco entre
 * as seções troca a direita na hora; tudo o que muda é gravado no mesmo instante (não há botão Salvar).
 *
 * Foco: começa na primeira seção; → entra nas linhas; ← numa linha volta para a seção; Voltar, da direita, vai para
 * a seção e, da esquerda, sai da tela. Nada aqui mostra usuário, senha, chave do TMDB ou o endereço completo da lista.
 */
class SettingsActivity : FragmentActivity() {

    private enum class Kind { CHOICE, TOGGLE, TEXT, ACTION, INFO }

    /** Uma linha da direita. [value] é lido de novo a cada atualização; numa linha INFO ele é o próprio texto. */
    private class Line(
        val label: String,
        val kind: Kind,
        val description: String? = null,
        val value: () -> String = { "" },
        val onClick: () -> Unit = {},
    )

    private class Section(val title: String, val description: String?, val lines: () -> List<Line>)

    private lateinit var store: PlaylistStore
    private lateinit var app: AppSettings

    private lateinit var rightPane: View
    private lateinit var titleView: TextView
    private lateinit var descView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var lineBox: LinearLayout
    private lateinit var sectionViews: List<TextView>

    private var current = -1
    private var shownLines: List<Line> = emptyList()
    private var shownViews: List<View> = emptyList()
    private var testing = false

    private val sections: List<Section> by lazy {
        listOf(
            Section("Conta", null, this::accountLines),
            Section("Reprodução", null, this::playbackLines),
            Section("Aparência", null, this::appearanceLines),
            Section("Atualização", null, this::updateLines),
            Section("Avançado", "Só mexa aqui se algo não estiver funcionando.", this::advancedLines),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = PlaylistStore(this)
        app = AppSettings(this)

        rightPane = findViewById(R.id.set_right)
        titleView = findViewById(R.id.set_title)
        descView = findViewById(R.id.set_desc)
        scroll = findViewById(R.id.set_scroll)
        lineBox = findViewById(R.id.set_lines)

        val sectionBox = findViewById<LinearLayout>(R.id.set_sections)
        sectionViews = sections.mapIndexed { index, section ->
            (layoutInflater.inflate(R.layout.item_setting_section, sectionBox, false) as TextView).also { view ->
                view.text = section.title
                // O conteúdo da direita acompanha o foco: não precisa apertar OK para ver a seção.
                view.setOnFocusChangeListener { _, has -> if (has) showSection(index) }
                view.setOnClickListener { showSection(index); focusFirstLine() }
                view.setOnKeyListener { _, code, ev -> onSectionKey(index, code, ev) }
                sectionBox.addView(view)
            }
        }

        showSection(0)
        sectionViews[0].requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (focusInRight()) focusSection() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Voltou do login ou da lista: conta, cache e versão podem ter mudado.
        if (current >= 0) refreshLines()
    }

    // ---------- seções e linhas ----------

    private fun accountLines() = listOf(
        Line(
            "Conta", Kind.ACTION, "Trocar ou conferir o usuário e a senha.",
            { SettingsText.accountState(store.playlistUrl, store.configUrl) },
        ) { startActivity(Intent(this, LoginActivity::class.java)) },
        Line(
            "Testar conexão", Kind.ACTION, "Confere a conta e se o servidor responde.",
            { if (testing) "Testando…" else "" },
        ) { testConnection() },
        Line("status", Kind.INFO, value = { statusText() }),
    )

    private fun playbackLines() = listOf(
        choice("Áudio preferido", "Usado quando o filme tem mais de um idioma.", AppSettings.LANG_OPTIONS, { app.audioLanguage }) {
            app.audioLanguage = it
        },
        choice("Legenda preferida", "Usado quando o filme tem legendas.", AppSettings.LANG_OPTIONS, { app.subtitleLanguage }) {
            app.subtitleLanguage = it
        },
        choice("Tamanho da legenda", null, AppSettings.SUBTITLE_SIZES, { app.subtitleSizeSp }) { app.subtitleSizeSp = it },
        choice("Pulo das setas", "Quanto avança ou volta ao apertar ← e →.", AppSettings.SEEK_STEPS, { app.seekStepSec }) {
            app.seekStepSec = it
        },
        toggle("Continuar de onde parou", "Retoma o filme ou episódio do ponto em que você parou.", { app.resumeEnabled }) {
            app.resumeEnabled = it
        },
    )

    private fun appearanceLines(): List<Line> {
        val lines = mutableListOf(wallpaperLine())
        if (effectiveWallpaper() == SettingsText.WALLPAPER_CUSTOM) {
            lines += text(
                "Imagem personalizada", "Endereço de uma imagem, por exemplo do Unsplash.", "https://…/imagem.jpg",
                secret = false, uri = true, current = { app.wallpaperUrl.ifBlank { null } },
                value = { SettingsText.urlSummary(app.wallpaperUrl) },
            ) { setWallpaperUrl(it) }
        }
        return lines
    }

    private fun updateLines() = listOf(
        Line("versão", Kind.INFO, value = { "Versão instalada: ${BuildConfig.VERSION_NAME}" }),
        toggle("Avisar quando houver versão nova", "Confere ao abrir o app e ao voltar para ele.", { app.autoUpdate }) {
            app.autoUpdate = it
        },
        Line("Verificar agora", Kind.ACTION) {
            Toast.makeText(this, "Verificando…", Toast.LENGTH_SHORT).show()
            Updater.check(this, silent = false)
        },
    )

    private fun advancedLines(): List<Line> {
        val aspects = PlayerController.ASPECTS.mapIndexed { index, aspect -> aspect.second to index }
        val connection = SettingsText.uniqueByValue(AppSettings.CONNECTION_OPTIONS)
        return listOf(
            choice("Formato da imagem", "Como o vídeo ocupa a tela.", aspects, { app.aspectIndex.coerceIn(aspects.indices) }) {
                app.aspectIndex = it
            },
            choice("Velocidade da internet", "Define com que qualidade o vídeo começa.", connection, { app.connectionMbps }) {
                app.connectionMbps = it
            },
            toggle("Sempre na qualidade máxima", "Não baixa a qualidade sozinho, mesmo se a internet engasgar.", { app.forceMaxQuality }) {
                app.forceMaxQuality = it
            },
            toggle("Reconectar sozinho quando falhar", "Tenta de novo quando o vídeo cai.", { app.autoRetry }) { app.autoRetry = it },
            refreshLine(),
            toggle("Catálogo pela conta (API)", "Desligado usa a lista M3U.", { app.useXtream }) { app.useXtream = it },
            userAgentLine(),
            text(
                "Chave do TMDB", "Capa, nota e sinopse dos filmes.", "Chave da API do themoviedb.org",
                secret = true, uri = false, current = { app.userTmdbApiKey },
                value = { SettingsText.tmdbState(app.userTmdbApiKey, BuildConfig.DEFAULT_TMDB_KEY) },
            ) { app.userTmdbApiKey = it.orEmpty() },
            text(
                "Guia de programação (URL XMLTV)", "Opcional: a programação já vem da conta.", "https://…/epg.xml",
                secret = true, uri = true, current = { store.epgUrl }, value = { SettingsText.urlSummary(store.epgUrl) },
            ) { store.epgUrl = it },
            text(
                "Lista M3U (URL)", null, "http://…/lista.m3u",
                secret = true, uri = true, current = { store.playlistUrl }, value = { SettingsText.urlSummary(store.playlistUrl) },
            ) { value -> changeList { store.playlistUrl = value } },
            text(
                "URL de configuração", "Arquivo na nuvem cujo conteúdo é o endereço da lista.", "https://…/config.txt",
                secret = true, uri = true, current = { store.configUrl }, value = { SettingsText.urlSummary(store.configUrl) },
            ) { value -> changeList { store.configUrl = value } },
            Line("Recarregar o catálogo agora", Kind.ACTION, "Baixa a lista de novo, sem esperar o intervalo.") { reloadCatalog() },
            Line("Limpar cache", Kind.ACTION, "Apaga a lista salva e o catálogo da conta.") { confirmClearCache() },
        )
    }

    private fun refreshLine() = Line(
        "Atualizar o catálogo", Kind.CHOICE, "De quanto em quanto tempo a lista é baixada de novo.",
        { SettingsText.refreshLabel(store.refreshHours) },
    ) {
        val options = SettingsText.REFRESH_OPTIONS
        // Um valor antigo fora das opções fica como está até o usuário escolher outra (nada é gravado ao abrir).
        pick("Atualizar o catálogo", options.map { it.first }, SettingsText.checkedIndex(options, store.refreshHours)) { i ->
            store.refreshHours = options[i].second
            refreshLines()
        }
    }

    private fun userAgentLine() = Line(
        "Identificação do player", Kind.CHOICE, "Mude se algum canal não abrir (erro de rede).",
        { SettingsText.userAgentLabel(store.userAgent, AppSettings.UA_PRESETS) },
    ) {
        val presets = AppSettings.UA_PRESETS
        pick("Identificação do player", presets.map { it.first }, SettingsText.userAgentIndex(store.userAgent, presets)) { i ->
            val ua = presets[i].second
            if (ua.isEmpty()) {
                editText("Identificação do player", "Ex.: MeuPlayer/1.0", store.userAgent, secret = false, uri = false) {
                    store.userAgent = it
                }
            } else {
                store.userAgent = ua
                refreshLines()
            }
        }
    }

    private fun wallpaperLine() = Line(
        "Papel de parede", Kind.CHOICE, null,
        { SettingsText.choiceLabel(AppSettings.WALLPAPERS, effectiveWallpaper()) },
    ) {
        val options = AppSettings.WALLPAPERS
        pick("Papel de parede", options.map { it.first }, SettingsText.checkedIndex(options, effectiveWallpaper())) { i ->
            val key = options[i].second
            // Imagem própria: o endereço é pedido em seguida. Os temas prontos valem na hora.
            if (key == SettingsText.WALLPAPER_CUSTOM) {
                editText("Imagem personalizada", "https://…/imagem.jpg", app.wallpaperUrl.ifBlank { null }, secret = false, uri = true) {
                    setWallpaperUrl(it)
                }
            } else {
                useBuiltInWallpaper(key)
            }
        }
    }

    private fun effectiveWallpaper() = SettingsText.effectiveWallpaper(app.wallpaper, app.wallpaperUrl)

    private fun useBuiltInWallpaper(key: String) {
        app.wallpaper = key
        if (app.wallpaperUrl.isNotBlank()) {
            // A Home dá preferência a qualquer URL preenchida; sem apagá-la o tema escolhido nunca apareceria.
            app.wallpaperUrl = ""
            Toast.makeText(this, "A imagem personalizada foi removida.", Toast.LENGTH_SHORT).show()
        }
        refreshLines()
    }

    private fun setWallpaperUrl(url: String?) {
        app.wallpaperUrl = url.orEmpty()
        val defaultKey = AppSettings.WALLPAPERS.first().second
        if (url != null) app.wallpaper = SettingsText.WALLPAPER_CUSTOM
        else if (app.wallpaper == SettingsText.WALLPAPER_CUSTOM) app.wallpaper = defaultKey
    }

    private fun toggle(label: String, description: String?, get: () -> Boolean, set: (Boolean) -> Unit) =
        Line(label, Kind.TOGGLE, description, { SettingsText.onOff(get()) }) {
            set(!get())
            refreshLines()
        }

    private fun <T> choice(
        label: String,
        description: String?,
        options: List<Pair<String, T>>,
        current: () -> T,
        apply: (T) -> Unit,
    ) = Line(label, Kind.CHOICE, description, { SettingsText.choiceLabel(options, current()) }) {
        pick(label, options.map { it.first }, SettingsText.checkedIndex(options, current())) { i ->
            apply(options[i].second)
            refreshLines()
        }
    }

    /** Linha de texto: o diálogo abre vazio quando o campo é sigiloso (ver [SettingsText.resolveText]). */
    private fun text(
        label: String,
        description: String?,
        placeholder: String,
        secret: Boolean,
        uri: Boolean,
        current: () -> String?,
        value: () -> String,
        apply: (String?) -> Unit,
    ) = Line(label, Kind.TEXT, description, value) { editText(label, placeholder, current(), secret, uri, apply) }

    /** Trocar a lista ou a URL de configuração invalida o catálogo em memória e o da conta, como sempre foi. */
    private fun changeList(write: () -> Unit) {
        val before = store.playlistUrl to store.configUrl
        write()
        if (store.playlistUrl to store.configUrl != before) {
            PlaylistHolder.clear()
            XtreamCatalog.clearCaches(applicationContext, store)
        }
    }

    // ---------- ações ----------

    private fun testConnection() {
        if (testing) return
        testing = true
        refreshLines()
        thread(name = "api-test") {
            val report = try {
                connectionReport()
            } catch (e: Exception) {
                // Só o tipo do erro: a mensagem de uma falha de rede pode trazer o endereço com usuário e senha.
                "O teste falhou (${e.javaClass.simpleName})."
            }
            runOnUiThread {
                testing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshLines()
                AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle("Teste da conexão")
                    .setMessage(report).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun connectionReport(): String {
        val raw = PlaylistHolder.channels.orEmpty()
        val account = XtreamCatalog.accountFor(store, raw)
        return if (account == null) {
            "Não achei usuário e senha.\nLista: " + XtreamApi.shape(store.lastUrl ?: store.playlistUrl) +
                "\nLinks /movie/ ou /series/ na lista: " +
                raw.count { it.url.contains("/movie/", true) || it.url.contains("/series/", true) }
        } else {
            XtreamApi.diagnose(account, store.userAgent)
        }
    }

    private fun reloadCatalog() {
        if (store.configUrl.isNullOrBlank() && store.playlistUrl.isNullOrBlank()) {
            Toast.makeText(this, "Entre com a sua conta, ou informe a lista, antes de recarregar.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_FROM_SETTINGS, true)
                .putExtra(ChannelListActivity.EXTRA_FORCE_REFRESH, true),
        )
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle("Limpar cache?")
            .setMessage("Apaga a lista salva e o catálogo da conta. Eles são baixados de novo quando você abrir a lista.")
            .setPositiveButton("Limpar") { _, _ ->
                store.clearCache()
                PlaylistHolder.clear()
                XtreamCatalog.clearCaches(applicationContext, store)
                refreshLines()
                Toast.makeText(this, "Cache limpo", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun statusText(): String {
        // Não lê o arquivo de cache (pode ter dezenas de MB) — usa a contagem salva.
        val last = store.lastRefreshMs.takeIf { it > 0 }
            ?.let { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(it)) }
        return SettingsText.cacheStatus(last, store.channelCount)
    }

    // ---------- diálogos ----------

    /** Lista de escolha única no tema dos diálogos do app, com a opção atual marcada. */
    private fun pick(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .show()
    }

    /**
     * Diálogo com um campo. Em campo sigiloso ([secret]) ele abre vazio, para o valor salvo nunca aparecer na tela;
     * só "Apagar" remove o que está salvo. Fora isso, Salvar com o campo vazio também apaga.
     */
    private fun editText(title: String, placeholder: String, current: String?, secret: Boolean, uri: Boolean, onResult: (String?) -> Unit) {
        val had = !current.isNullOrBlank()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        if (secret && had) {
            box.addView(TextView(this).apply {
                text = "Já existe um valor salvo e, por segurança, ele não aparece aqui. Digite um novo para trocar, " +
                    "deixe vazio para manter ou use Apagar."
                setTextColor(ContextCompat.getColor(context, R.color.lf_text_2))
                textSize = 13f
            })
        }
        val field = EditText(this, null, 0, R.style.TvEditText).apply {
            hint = placeholder
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            if (!secret) {
                setText(current.orEmpty())
                setSelection(text.length)
            }
        }
        box.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val builder = AlertDialog.Builder(this, R.style.Theme_Player_Dialog).setTitle(title).setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                when (val edit = SettingsText.resolveText(field.text.toString(), had, secret)) {
                    SettingsText.TextEdit.Keep -> Unit
                    SettingsText.TextEdit.Clear -> onResult(null)
                    is SettingsText.TextEdit.Set -> onResult(edit.value)
                }
                refreshLines()
            }
            .setNegativeButton("Cancelar", null)
        if (had) {
            builder.setNeutralButton("Apagar") { _, _ ->
                onResult(null)
                refreshLines()
            }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    // ---------- tela ----------

    private fun showSection(index: Int) {
        if (index == current) return
        current = index
        sectionViews.forEachIndexed { i, view -> view.isActivated = i == index }
        val section = sections[index]
        titleView.text = section.title
        descView.text = section.description.orEmpty()
        descView.visibility = if (section.description == null) View.GONE else View.VISIBLE
        buildLines()
        scroll.scrollTo(0, 0)
    }

    private fun buildLines() {
        val lines = sections[current].lines()
        lineBox.removeAllViews()
        shownLines = lines
        shownViews = lines.map { line ->
            val layout = if (line.kind == Kind.INFO) R.layout.item_setting_info else R.layout.item_setting_row
            layoutInflater.inflate(layout, lineBox, false).also { view ->
                if (line.kind != Kind.INFO) view.setOnKeyListener { v, code, ev -> onLineKey(v, code, ev) }
                bindLine(view, line)
                lineBox.addView(view)
            }
        }
    }

    /** Atualiza valores sem recriar as linhas (a rolagem e o foco ficam onde estão); recria só se o conjunto mudou. */
    private fun refreshLines() {
        if (current < 0) return
        val fresh = sections[current].lines()
        if (fresh.map { it.label } == shownLines.map { it.label }) {
            shownLines = fresh
            fresh.forEachIndexed { i, line -> bindLine(shownViews[i], line) }
            return
        }
        val focusedAt = shownViews.indexOfFirst { it.hasFocus() }
        val focusedLabel = shownLines.getOrNull(focusedAt)?.label
        buildLines()
        if (focusedAt < 0) return
        // A linha que tinha o foco pode ter sumido (ex.: "Imagem personalizada" apagada): vai para a anterior.
        val target = shownLines.indexOfFirst { it.label == focusedLabel }.takeIf { it >= 0 } ?: (focusedAt - 1).coerceAtLeast(0)
        val view = shownViews.getOrNull(target)?.takeIf { it.isFocusable }
        if (view != null) view.requestFocus() else focusFirstLine()
    }

    private fun bindLine(view: View, line: Line) {
        if (line.kind == Kind.INFO) {
            (view as TextView).text = line.value()
            return
        }
        val value = line.value()
        view.findViewById<TextView>(R.id.row_label).text = line.label
        view.findViewById<TextView>(R.id.row_desc).apply {
            text = line.description.orEmpty()
            visibility = if (line.description == null) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.row_value).apply {
            text = value
            visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            // Desligado fica no cinza de apoio, mas o estado é sempre dito por extenso ("Desligado"), nunca só pela cor.
            val off = line.kind == Kind.TOGGLE && value == SettingsText.onOff(false)
            setTextColor(ContextCompat.getColor(context, if (off) R.color.lf_text_2 else R.color.lf_accent))
        }
        view.contentDescription = SettingsText.describe(line.label, value, toggle = line.kind == Kind.TOGGLE)
        view.setOnClickListener { line.onClick() }
    }

    // ---------- foco ----------

    private fun focusSection() {
        sectionViews[current].requestFocus()
    }

    private fun focusFirstLine(): Boolean = shownViews.firstOrNull { it.isFocusable }?.requestFocus() ?: false

    private fun focusInRight(): Boolean {
        var v: View? = currentFocus
        while (v != null) {
            if (v === rightPane) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * ↑/↓ andam entre as seções e nunca saem da coluna: o foco por geometria do Android acharia uma linha da direita
     * (mais abaixo) quando se aperta ↓ na última seção. → entra nas linhas; ← não tem para onde ir.
     */
    private fun onSectionKey(index: Int, code: Int, ev: KeyEvent): Boolean {
        val down = ev.action == KeyEvent.ACTION_DOWN
        return when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) focusFirstLine(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> true
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) sectionViews.getOrNull(index - 1)?.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) sectionViews.getOrNull(index + 1)?.requestFocus(); true }
            else -> false
        }
    }

    /** ← volta para a seção. ↑ na primeira linha e ↓ na última são engolidos, pelo mesmo motivo de [onSectionKey]. */
    private fun onLineKey(view: View, code: Int, ev: KeyEvent): Boolean {
        val rows = shownViews.filter { it.isFocusable }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) focusSection(); true }
            KeyEvent.KEYCODE_DPAD_UP -> rows.firstOrNull() === view
            KeyEvent.KEYCODE_DPAD_DOWN -> rows.lastOrNull() === view
            else -> false
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
