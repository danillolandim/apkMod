package com.seupainel.player.net

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cliente OkHttp com resolução de DNS resiliente:
 *  1) tenta o DNS do sistema (rápido, funciona na maioria dos casos);
 *  2) se falhar (ex.: operadora/TV bloqueia o domínio `.sbs`), tenta
 *     DNS-over-HTTPS na Cloudflare, que ignora o DNS da TV.
 *
 * Isso conserta "Unable to resolve host" sem mexer no DNS da TV.
 */
object Net {

    private val resilientDns by lazy { ResilientDns() }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(resilientDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Mantém mais conexões vivas por mais tempo -> ao trocar de canal
            // no mesmo servidor, reaproveita a conexão (zapping mais rápido).
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Cliente da REPRODUÇÃO: mesma rede resiliente (DNS, pool de conexões), mas falha mais cedo.
     * Esperar 30 s por um servidor que não responde é o que parece "travado".
     */
    val playerClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** true enquanto um vídeo está na tela: trabalhos de fundo (TMDB, catálogo) esperam. */
    @Volatile var playbackActive = false

    /** Bloqueia o chamador (thread de fundo) enquanto um vídeo toca, para não disputar a rede. */
    fun awaitIdle() {
        while (playbackActive) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Abre a conexão com o servidor do vídeo ANTES de o usuário apertar Assistir: resolve o DNS, faz
     * o TLS e segue o redirecionamento do provedor. Pede só 1 byte; o player reaproveita a conexão.
     */
    fun prewarm(url: String, userAgent: String?) {
        warmExec.execute {
            runCatching {
                val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0")
                    .apply { if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent) }.build()
                playerClient.newCall(req).execute().use { }
            }
        }
    }

    private val warmExec by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Pré-resolve o DNS dos hosts da lista em 2º plano, para que a primeira
     * conexão ao tocar um canal já tenha o IP em cache (reduz o tempo de troca).
     */
    fun warmDns(hosts: Collection<String>) {
        hosts.asSequence().filter { it.isNotBlank() }.distinct().take(12).forEach { h ->
            warmExec.execute { runCatching { resilientDns.lookup(h) } }
        }
    }

    private class ResilientDns : Dns {
        // Cliente só para o DoH (usa DNS do sistema apenas para o bootstrap).
        private val doh: DnsOverHttps by lazy {
            val bootstrap = OkHttpClient.Builder().build()
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                )
                .build()
        }

        // Hosts que o DNS do sistema (da TV) não resolve -> vão direto pro DoH
        // nas próximas vezes, sem pagar a falha do sistema de novo.
        private val systemBlocked = java.util.Collections.synchronizedSet(HashSet<String>())
        // Cache curto dos resultados do DoH (o sistema já tem cache próprio).
        private class Cached(val addrs: List<InetAddress>, val at: Long)
        private val dohCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
        private val ttlMs = 5 * 60 * 1000L

        override fun lookup(hostname: String): List<InetAddress> {
            // 1) sistema (pula se já sabemos que ele bloqueia este host)
            if (hostname !in systemBlocked) {
                runCatching { Dns.SYSTEM.lookup(hostname) }
                    .onSuccess { if (it.isNotEmpty()) return it }
                systemBlocked.add(hostname)
            }
            // 2) DNS-over-HTTPS, com cache curto (acelera o zapping)
            dohCache[hostname]?.let { if (System.currentTimeMillis() - it.at < ttlMs) return it.addrs }
            val r = runCatching { doh.lookup(hostname) }
                .getOrElse { throw java.net.UnknownHostException("Falha ao resolver $hostname (sistema e DoH)") }
            dohCache[hostname] = Cached(r, System.currentTimeMillis())
            return r
        }
    }
}
