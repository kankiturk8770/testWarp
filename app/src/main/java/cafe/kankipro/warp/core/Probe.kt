package cafe.kankipro.warp.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Proves the tunnel carries traffic: an HTTPS request to Cloudflare's `/cdn-cgi/trace`,
 * sent through the core's own SOCKS5 port. Only a real answer from a host that proved
 * it is Cloudflare (TLS, hostname checked) counts; an open port proves nothing.
 * The answer also says where the traffic comes out (ip, colo, loc, warp=on).
 */
object Probe {
    data class Egress(
        val ip: String,
        val colo: String = "",
        val loc: String = "",
        val warp: String = "",
        /** Time from request to first byte of the answer, through the tunnel. */
        val pingMs: Long = 0,
    )

    private val HOSTS = listOf("cloudflare.com", "one.one.one.one", "www.cloudflare.com")

    fun trace(port: Int, timeoutMs: Int = 6000): Egress? {
        for (host in HOSTS) {
            val got = try {
                once(port, host, timeoutMs)
            } catch (_: Exception) {
                null
            }
            if (got != null) return got
        }
        return null
    }

    private fun once(port: Int, host: String, timeoutMs: Int): Egress? {
        Socket().use { raw ->
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress("127.0.0.1", port), 1500)
            socks5Connect(raw, host, 443)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            ssl.use { s ->
                s.soTimeout = timeoutMs
                s.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) return null
                val req = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $host\r\nUser-Agent: KankiWarp\r\n" +
                    "Accept: */*\r\nConnection: close\r\n\r\n"
                val started = System.nanoTime()
                s.outputStream.apply { write(req.toByteArray()); flush() }
                val input = s.inputStream
                val first = input.read()
                if (first < 0) return null
                val ping = (System.nanoTime() - started) / 1_000_000
                val text = String(byteArrayOf(first.toByte()) + readCapped(input, 32 * 1024))
                val body = text.substringAfter("\r\n\r\n", "")
                if (!text.startsWith("HTTP/1.1 200") && !text.startsWith("HTTP/1.0 200")) return null
                return parseTrace(body)?.copy(pingMs = ping)
            }
        }
    }

    /** `key=value` lines of /cdn-cgi/trace; null without an `ip`. */
    fun parseTrace(body: String): Egress? {
        val map = body.lineSequence()
            .mapNotNull { line -> line.trim().split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val ip = map["ip"]?.takeIf { it.isNotBlank() } ?: return null
        return Egress(ip = ip, colo = map["colo"].orEmpty(), loc = map["loc"].orEmpty(), warp = map["warp"].orEmpty())
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < cap) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                -1
            }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** SOCKS5 CONNECT by name, so the name is resolved inside the tunnel, not on the phone. */
    private fun socks5Connect(s: Socket, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val hello = readN(inp, 2)
        require(hello[0].toInt() == 5 && hello[1].toInt() == 0) { "socks5: no-auth refused" }
        val name = host.toByteArray()
        val req = byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        out.write(req); out.flush()
        val head = readN(inp, 4)
        require(head[1].toInt() == 0) { "socks5: connect refused (${head[1]})" }
        val skip = when (head[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> readN(inp, 1)[0].toInt() and 0xff
            else -> error("socks5: bad address type")
        }
        readN(inp, skip + 2)
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(b, got, n - got)
            if (r < 0) error("socks5: closed")
            got += r
        }
        return b
    }
}
