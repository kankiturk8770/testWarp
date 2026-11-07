package cafe.kankipro.warp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The white-list hunt for MASQUE over HTTP/2: which Cloudflare MASQUE addresses still complete a
 * TLS handshake on TCP 443 from this network (on a cut-off network a few ranges stay open). Only
 * reachability: nothing is sent after the handshake. The MASQUE gateways use their own certificate,
 * which the phone does not trust, so a handshake that ends at the certificate check counts as an
 * answer (the server spoke TLS back); a timeout or a reset does not.
 */
object H2Scan {
    /** The core's MASQUE ranges (prober.rs MASQUE_CIDRS_V4), WARP's own gateways first. */
    val PREFIXES = listOf(
        "162.159.198", "162.159.199", "162.159.192", "162.159.195", "162.159.196", "162.159.193",
        "162.159.197", "162.159.204",
    )
    private const val SNI = "consumer-masque.cloudflareclient.com"

    suspend fun scan(perPrefix: Int = 64, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> }): List<WgScan.Hit> =
        withContext(Dispatchers.IO) {
            val hosts = PREFIXES.flatMap { p -> (listOf(1) + (2..254).shuffled().take(perPrefix - 1)).map { "$p.$it" } }
            val gate = Semaphore(32)
            var done = 0
            var found = 0
            val hits = coroutineScope {
                hosts.map { h ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            val ms = handshake(h, 2500)
                            synchronized(this@H2Scan) {
                                done++
                                if (ms != null) found++
                                onProgress(done, hosts.size, found)
                            }
                            ms?.let { WgScan.Hit("$h:443", it, 0, 0) }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            hits.sortedBy { it.pingMs }
        }

    /** TCP connect + TLS handshake time in ms, or null when the address does not answer. */
    fun handshake(host: String, timeoutMs: Int): Int? {
        val start = System.nanoTime()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, 443), timeoutMs)
                raw.soTimeout = timeoutMs
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, SNI, 443, true) as SSLSocket
                ssl.use { s ->
                    try {
                        s.startHandshake()
                    } catch (e: SSLHandshakeException) {
                        if (!isCertProblem(e)) return null
                    }
                }
                ((System.nanoTime() - start) / 1_000_000).toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isCertProblem(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is CertificateException) return true
            if (c.message?.contains("certificate", ignoreCase = true) == true ||
                c.message?.contains("trust anchor", ignoreCase = true) == true) return true
            c = c.cause
        }
        return false
    }
}
