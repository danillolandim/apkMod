package com.seupainel.player

/**
 * Textos e decisões da tela de Configurações sem Android (testados em JVM).
 *
 * Regra de privacidade: nada daqui devolve usuário, senha, chave ou URL completa. Endereços viram só o host.
 */
object SettingsText {

    const val NOT_SET = "Não definida"
    const val SET = "Definida"
    const val FACTORY = "De fábrica"
    const val CONNECTED = "Conectada"
    const val NOT_CONFIGURED = "Não configurada"
    const val DEFAULT_PLAYER_ID = "Padrão do app"
    const val CUSTOM = "Personalizado"
    const val OTHER = "Outro"

    /** Chave de papel de parede que significa "uma imagem minha, por URL" (ver AppSettings.WALLPAPERS). */
    const val WALLPAPER_CUSTOM = "custom"

    /** De quanto em quanto tempo baixar o catálogo de novo (rótulo -> horas; 0 = só quando o usuário pedir). */
    val REFRESH_OPTIONS = listOf(
        "A cada 6 horas" to 6,
        "A cada 12 horas" to 12,
        "Uma vez por dia" to 24,
        "Só quando eu pedir" to 0,
    )

    /**
     * Só o host de um endereço, sem usuário, senha, porta, caminho nem parâmetros. Null quando não há nada que
     * pareça um host (vazio, ou texto solto com espaços).
     */
    fun hostOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val rest = text.substringAfter("://", text)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        // O que vem antes do último "@" é usuário:senha — nunca entra no resultado.
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) hostAndPort.substringBefore(']') + "]" else hostAndPort.substringBefore(':')
        return host.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }
    }

    /** O que a linha de um endereço mostra: o host, "Definida" se não der para ler o host, ou "Não definida". */
    fun urlSummary(url: String?): String = when {
        url.isNullOrBlank() -> NOT_SET
        else -> hostOf(url) ?: SET
    }

    /** Chave do TMDB: a do usuário vence a de fábrica; nenhuma das duas = não definida. */
    fun tmdbState(userKey: String?, factoryKey: String?): String = when {
        !userKey.isNullOrBlank() -> SET
        !factoryKey.isNullOrBlank() -> FACTORY
        else -> NOT_SET
    }

    /** A conta vale como conectada quando há lista ou URL de configuração salva. */
    fun accountState(playlistUrl: String?, configUrl: String?): String =
        if (!playlistUrl.isNullOrBlank() || !configUrl.isNullOrBlank()) CONNECTED else NOT_CONFIGURED

    fun onOff(on: Boolean): String = if (on) "Ligado" else "Desligado"

    /** Leitor de tela: "rótulo, valor"; liga/desliga fica "rótulo, ligado". Sem valor, só o rótulo. */
    fun describe(label: String, value: String, toggle: Boolean = false): String = when {
        value.isBlank() -> label
        toggle -> "$label, ${value.lowercase()}"
        else -> "$label, $value"
    }

    /** Rótulo da opção que está salva; [unknown] quando o valor salvo não é nenhuma das opções. */
    fun <T> choiceLabel(options: List<Pair<String, T>>, current: T, unknown: String = OTHER): String =
        options.firstOrNull { it.second == current }?.first ?: unknown

    /** Posição da opção salva na lista (a marcada no diálogo), ou -1 quando nenhuma é. */
    fun <T> checkedIndex(options: List<Pair<String, T>>, current: T): Int =
        options.indexOfFirst { it.second == current }

    /** Duas opções com o mesmo valor não dá para distinguir depois de salvas: fica a primeira de cada valor. */
    fun <T> uniqueByValue(options: List<Pair<String, T>>): List<Pair<String, T>> = options.distinctBy { it.second }

    /** Intervalo do catálogo; um valor antigo fora das opções continua aparecendo como "A cada N horas". */
    fun refreshLabel(hours: Int): String =
        REFRESH_OPTIONS.firstOrNull { it.second == hours }?.first ?: when {
            hours <= 0 -> REFRESH_OPTIONS.first { it.second == 0 }.first
            hours == 1 -> "A cada hora"
            else -> "A cada $hours horas"
        }

    /** Identificação do player: vazia = a do app; uma das prontas = o nome dela; qualquer outra = Personalizado. */
    fun userAgentLabel(userAgent: String?, presets: List<Pair<String, String>>): String {
        val ua = userAgent?.trim().orEmpty()
        if (ua.isEmpty()) return DEFAULT_PLAYER_ID
        return presets.firstOrNull { it.second.isNotEmpty() && it.second == ua }?.first ?: CUSTOM
    }

    /** Opção marcada no diálogo da identificação: a pronta que combina, senão a primeira ("Personalizado / vazio"). */
    fun userAgentIndex(userAgent: String?, presets: List<Pair<String, String>>): Int {
        val ua = userAgent?.trim().orEmpty()
        val i = presets.indexOfFirst { it.second.isNotEmpty() && it.second == ua }
        return if (i >= 0) i else 0
    }

    /**
     * Papel de parede que a Home realmente usa: qualquer URL preenchida vence o tema escolhido (é assim que a
     * Home sempre funcionou), então ela é a escolha em vigor mesmo que a chave salva seja outra.
     */
    fun effectiveWallpaper(key: String, url: String): String =
        if (url.isNotBlank() || key == WALLPAPER_CUSTOM) WALLPAPER_CUSTOM else key

    /** Linha de apoio da conta: última atualização (já formatada, null = nunca) e itens em cache. */
    fun cacheStatus(lastRefresh: String?, items: Int): String =
        "Última atualização: ${lastRefresh ?: "nunca"}" + if (items > 0) " • $items canais em cache" else " • sem cache"

    /** O que fazer com o texto digitado no diálogo de um campo. */
    sealed class TextEdit {
        /** Deixa o valor salvo como está. */
        object Keep : TextEdit()

        /** Apaga o valor salvo. */
        object Clear : TextEdit()

        data class Set(val value: String) : TextEdit()
    }

    /**
     * Campos sigilosos (lista, configuração, guia, chave) abrem vazios para não mostrar o valor salvo; por isso
     * "Salvar" vazio não pode apagar sem querer: só o botão "Apagar" apaga. Nos demais, campo vazio = apagar.
     */
    fun resolveText(typed: String, hadValue: Boolean, secret: Boolean): TextEdit {
        val text = typed.trim()
        return when {
            text.isNotEmpty() -> TextEdit.Set(text)
            secret && hadValue -> TextEdit.Keep
            else -> TextEdit.Clear
        }
    }
}
