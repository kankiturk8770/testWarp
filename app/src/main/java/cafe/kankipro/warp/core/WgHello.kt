package cafe.kankipro.warp.core

import java.security.SecureRandom
import java.util.Base64

/*
 * پینگ واقعی WireGuard / AmneziaWG: همان اولین بسته‌ی دست‌دادن (handshake initiation) که خود برنامه
 * موقع اتصال می‌فرستد، با کلید همین کانفیگ. سرور فقط وقتی جواب می‌دهد که این کاربر (کلید عمومی‌اش) را
 * واقعاً داشته باشد؛ پس کانفیگی که از سرور پاک شده، یا سروری که خاموش است، یا مسیری که فیلتر شده،
 * «بدون پینگ» دیده می‌شود (نه مثل پینگ ICMP که فقط زنده بودن آدرس را می‌گوید، که برای تانل‌ها سرور
 * ایران است). زمان رفت و برگشت همین بسته پینگ واقعی تا سروری است که ترافیک از آن خارج می‌شود.
 *
 * AmneziaWG: Jc بسته‌ی بی‌معنی (Jmin تا Jmax بایت) قبل از دست‌دادن، S1 بایت تصادفی جلوی بسته‌ی
 * دست‌دادن، S2 جلوی جواب، و نوع پیام‌ها H1/H2 (عدد یا بازه‌ی «a-b» در نسخه‌ی ۲).
 * این‌جا فقط ساختن و سنجیدن بسته‌هاست (بدون شبکه و اندروید)؛ فرستادن در Probe.kt است.
 */
class WgHello private constructor(
    private val priv: ByteArray,
    private val pub: ByteArray,
    private val serverPub: ByteArray,
    private val psk: ByteArray,
    val host: String,
    val port: Int,
    private val awg: Awg,
) {
    /** پارامترهای AmneziaWG (برای WireGuard ساده همه صفر و نوع‌ها ۱ و ۲) */
    data class Awg(val jc: Int, val jmin: Int, val jmax: Int, val s1: Int, val s2: Int, val h1: LongRange, val h2: LongRange, val h3: LongRange)

    /** وضعیت یک دست‌دادن فرستاده‌شده تا جوابش سنجیده شود */
    class Sent internal constructor(
        val junk: List<ByteArray>,
        val packet: ByteArray,
        internal val sender: Int,
        internal val ePriv: ByteArray,
        internal val ck: ByteArray,
        internal val h: ByteArray,
    )

    private val rnd = SecureRandom()

    fun hello(nowMs: Long = System.currentTimeMillis()): Sent {
        var ck = KCrypto.hash(CONSTRUCTION)
        var h = KCrypto.hash(ck, IDENTIFIER)
        h = KCrypto.hash(h, serverPub)
        val ePriv = ByteArray(32).also { rnd.nextBytes(it) }
        val ePub = KCrypto.publicKey(ePriv)
        ck = kdf(ck, ePub, 1)[0]
        h = KCrypto.hash(h, ePub)
        var k: ByteArray
        kdf(ck, KCrypto.x25519(ePriv, serverPub), 2).let { ck = it[0]; k = it[1] }
        val encStatic = KCrypto.seal(k, KCrypto.wgNonce(0), pub, h)
        h = KCrypto.hash(h, encStatic)
        kdf(ck, KCrypto.x25519(priv, serverPub), 2).let { ck = it[0]; k = it[1] }
        val encTime = KCrypto.seal(k, KCrypto.wgNonce(0), tai64n(nowMs), h)
        h = KCrypto.hash(h, encTime)

        val sender = rnd.nextInt()
        val msg = ByteArray(INIT_LEN)
        putLe32(msg, 0, pick(awg.h1))
        putLe32(msg, 4, sender.toLong())
        ePub.copyInto(msg, 8)
        encStatic.copyInto(msg, 40)
        encTime.copyInto(msg, 88)
        val mac1 = KCrypto.blake2s(msg.copyOf(116), 16, KCrypto.hash(LABEL_MAC1, serverPub))
        mac1.copyInto(msg, 116)
        // mac2 صفر می‌ماند (فقط وقتی سرور زیر فشار کوکی خواسته باشد لازم است)
        val pad = ByteArray(awg.s1).also { rnd.nextBytes(it) }
        val junk = List(awg.jc) {
            val n = if (awg.jmax > awg.jmin) awg.jmin + rnd.nextInt(awg.jmax - awg.jmin + 1) else awg.jmin
            ByteArray(n.coerceIn(1, 1280)).also { j -> rnd.nextBytes(j) }
        }
        return Sent(junk, pad + msg, sender, ePriv, ck, h)
    }

    /**
     * جواب سرور برای همین دست‌دادن است؟ (نوع، شماره‌ی ما، و برچسب رمز که فقط سروری که کلید خصوصی
     * خودش و کلید این کاربر را دارد می‌تواند درست بسازد). جواب «کوکی» (سرور زیر فشار) هم یعنی سرور
     * زنده است و بسته را خوانده.
     */
    fun answers(packet: ByteArray, s: Sent): Boolean {
        if (packet.size == awg.s2 + RESP_LEN) {
            val m = packet.copyOfRange(awg.s2, packet.size)
            if (getLe32(m, 0) !in awg.h2 || getLe32(m, 8).toInt() != s.sender) return false
            val eR = m.copyOfRange(12, 44)
            var ck = kdf(s.ck, eR, 1)[0]
            var h = KCrypto.hash(s.h, eR)
            ck = kdf(ck, KCrypto.x25519(s.ePriv, eR), 1)[0]
            ck = kdf(ck, KCrypto.x25519(priv, eR), 1)[0]
            val t = kdf(ck, psk, 3)
            h = KCrypto.hash(h, t[1])
            return KCrypto.open(t[2], KCrypto.wgNonce(0), m.copyOfRange(44, 60), h) != null
        }
        // پیام کوکی: [نوع][شماره‌ی ما][۲۴ nonce][۳۲ کوکی رمزشده] (در AmneziaWG 2 با پیشوند تصادفی)
        if (packet.size >= COOKIE_LEN) {
            val m = packet.copyOfRange(packet.size - COOKIE_LEN, packet.size)
            return getLe32(m, 0) in awg.h3 && getLe32(m, 4).toInt() == s.sender
        }
        return false
    }

    private fun pick(r: LongRange): Long = if (r.first == r.last) r.first else r.first + (rnd.nextLong() and Long.MAX_VALUE) % (r.last - r.first + 1)

    companion object {
        private val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray()
        private val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray()
        private val LABEL_MAC1 = "mac1----".toByteArray()
        const val INIT_LEN = 148
        const val RESP_LEN = 92
        const val COOKIE_LEN = 64

        /** KDF وایرگارد با HMAC-BLAKE2s: n کلید ۳۲ بایتی */
        internal fun kdf(key: ByteArray, input: ByteArray, n: Int): List<ByteArray> {
            val t0 = KCrypto.hmac(key, input)
            val out = ArrayList<ByteArray>(n)
            var prev = ByteArray(0)
            for (i in 1..n) {
                prev = KCrypto.hmac(t0, prev + byteArrayOf(i.toByte()))
                out.add(prev)
            }
            return out
        }

        /** TAI64N: ۸ بایت ثانیه (از 2^62 + 10) و ۴ بایت نانوثانیه، big-endian */
        internal fun tai64n(ms: Long): ByteArray {
            val sec = 0x400000000000000aL + ms / 1000
            val nano = (ms % 1000) * 1_000_000
            val b = ByteArray(12)
            for (i in 0 until 8) b[i] = (sec ushr (56 - 8 * i)).toByte()
            for (i in 0 until 4) b[8 + i] = (nano ushr (24 - 8 * i)).toByte()
            return b
        }

        private fun putLe32(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte()
        }

        private fun getLe32(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xff) shl (8 * i))
            return v
        }

        /** «123» یا «100-200» (AmneziaWG 2)؛ پیش‌فرض نوع ساده‌ی وایرگارد */
        private fun range(v: String?, def: Long): LongRange {
            val s = v?.trim().orEmpty()
            if (s.isEmpty()) return def..def
            val p = s.split("-").mapNotNull { it.trim().toLongOrNull() }
            return when {
                p.size == 2 && p[0] <= p[1] -> p[0]..p[1]
                p.size == 1 -> p[0]..p[0]
                else -> def..def
            }
        }

        /** از متن کانفیگ WireGuard / AmneziaWG؛ null اگر چیزی که لازم است نبود */
        fun of(cfg: String): WgHello? = runCatching {
            val kv = HashMap<String, String>()
            cfg.lines().forEach { line ->
                val p = line.split("=", limit = 2)
                if (p.size == 2) kv.putIfAbsent(p[0].trim().lowercase(), p[1].trim())
            }
            val dec = Base64.getDecoder()
            val priv = dec.decode(kv["privatekey"] ?: return null)
            val spub = dec.decode(kv["publickey"] ?: return null)
            val psk = kv["presharedkey"]?.takeIf { it.isNotBlank() }?.let { dec.decode(it) } ?: ByteArray(32)
            if (priv.size != 32 || spub.size != 32 || psk.size != 32) return null
            val ep = kv["endpoint"] ?: return null
            val host = ep.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
            val port = ep.substringAfterLast(":").trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isBlank()) return null
            fun i(k: String) = kv[k]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val awg = Awg(
                jc = i("jc").coerceAtMost(16), jmin = i("jmin"), jmax = i("jmax"),
                s1 = i("s1").coerceAtMost(1024), s2 = i("s2").coerceAtMost(1024),
                h1 = range(kv["h1"], 1), h2 = range(kv["h2"], 2), h3 = range(kv["h3"], 3),
            )
            WgHello(priv, KCrypto.publicKey(priv), spub, psk, host, port, awg)
        }.getOrNull()
    }
}
