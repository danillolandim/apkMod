package com.seupainel.player.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
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
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
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

        override fun lookup(hostname: String): List<InetAddress> {
            // 1) sistema
            runCatching { Dns.SYSTEM.lookup(hostname) }
                .onSuccess { if (it.isNotEmpty()) return it }
            // 2) DNS-over-HTTPS
            return runCatching { doh.lookup(hostname) }
                .getOrElse { throw java.net.UnknownHostException("Falha ao resolver $hostname (sistema e DoH)") }
        }
    }
}
