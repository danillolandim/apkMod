package com.seupainel.player

/**
 * Decide quando vale repetir uma falha de reprodução e explica o motivo ao usuário.
 *
 * Repetir só ajuda em falha passageira (rede, servidor ocupado). Arquivo que não existe ou
 * formato que a TV não abre nunca melhora: nesses casos a falha tem que aparecer na hora, não
 * depois de um minuto de "carregando".
 */
object PlaybackErrors {

    enum class Kind { NETWORK, SERVER_BUSY, DENIED, NOT_FOUND, UNSUPPORTED, OTHER }

    fun kindOf(httpCode: Int?, isParser: Boolean, isNotFound: Boolean, isNetwork: Boolean): Kind = when {
        isParser -> Kind.UNSUPPORTED
        isNotFound -> Kind.NOT_FOUND
        httpCode != null -> when (httpCode) {
            404, 410 -> Kind.NOT_FOUND
            401, 403 -> Kind.DENIED
            408, 429 -> Kind.SERVER_BUSY
            in 500..599 -> Kind.SERVER_BUSY
            else -> Kind.OTHER
        }
        isNetwork -> Kind.NETWORK
        else -> Kind.OTHER
    }

    /** Espera em ms antes de tentar de novo, ou null se não adianta repetir. [errorCount] começa em 1. */
    fun retryDelayMs(kind: Kind, errorCount: Int, live: Boolean): Long? = when (kind) {
        Kind.NETWORK -> if (errorCount <= (if (live) 5 else 2)) minOf(600L * errorCount, 2_500L) else null
        Kind.SERVER_BUSY -> if (errorCount <= 3) minOf(800L * errorCount, 3_000L) else null
        // Uma tentativa extra: a conexão anterior da conta pode ainda estar fechando no servidor.
        Kind.DENIED -> if (errorCount <= 1) 1_000L else null
        Kind.OTHER -> if (errorCount <= 1) 800L else null
        Kind.NOT_FOUND, Kind.UNSUPPORTED -> null
    }

    fun isTransient(kind: Kind) = kind == Kind.NETWORK || kind == Kind.SERVER_BUSY

    fun message(kind: Kind, httpCode: Int?): String {
        val code = httpCode?.let { " (HTTP $it)" }.orEmpty()
        return when (kind) {
            Kind.NETWORK -> "Sem resposta do servidor. Confira a internet ou tente de novo."
            Kind.SERVER_BUSY -> "O servidor do provedor está ocupado ou com problema$code. Tente de novo em instantes."
            Kind.DENIED -> "O servidor recusou o acesso$code. Pode ser limite de conexões (outro aparelho usando a conta), " +
                "conta vencida ou bloqueio. Veja Configurações > Testar API."
            Kind.NOT_FOUND -> "Este vídeo não está mais disponível no servidor$code."
            Kind.UNSUPPORTED -> "Este arquivo está num formato que a TV não consegue abrir."
            Kind.OTHER -> "Não foi possível reproduzir."
        }
    }
}
