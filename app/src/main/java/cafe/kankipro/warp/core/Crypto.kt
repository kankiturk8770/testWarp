package cafe.kankipro.warp.core

import java.math.BigInteger

/*
 * رمزنگاری کوچکی که پینگ واقعی لازم دارد (بدون وابستگی به اندروید، تا روی کامپیوتر هم تست شود):
 *   X25519 (RFC 7748)، BLAKE2s (RFC 7693) و HMAC آن، ChaCha20-Poly1305 (RFC 8439).
 * اندروید ۸ و ۹ (minSdk 26) ChaCha20-Poly1305 و X25519 را در JCA ندارند، پس همین‌جا نوشته شده‌اند.
 * فقط برای چند بسته‌ی پینگ به کار می‌روند؛ سرعت مهم نیست، درستی مهم است (تست با بردارهای RFC).
 */
object KCrypto {
    // ------------------------------------------------------------------ X25519
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun toLe32(x: BigInteger): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    /** X25519(scalar, u): ۳۲ بایت little-endian */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val uu = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = le(uu).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        val kn = le(k)
        for (t in 254 downTo 0) {
            val kt = if (kn.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = x1.multiply(da.subtract(cb).mod(P).let { it.multiply(it).mod(P) }).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        // BigInteger.TWO فقط از اندروید ۱۳ هست
        return toLe32(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }
    fun publicKey(priv: ByteArray): ByteArray = x25519(priv, BASE)

    // ------------------------------------------------------------------ BLAKE2s
    private val IV = intArrayOf(
        0x6A09E667, -0x4498517b, 0x3C6EF372, -0x5ab00ac6,
        0x510E527F, -0x64fa9774, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    private fun rd32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun wr32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    private fun compress(h: IntArray, block: ByteArray, t: Long, last: Boolean) {
        val m = IntArray(16) { rd32(block, it * 4) }
        val v = IntArray(16)
        for (i in 0 until 8) { v[i] = h[i]; v[i + 8] = IV[i] }
        v[12] = v[12] xor t.toInt()
        v[13] = v[13] xor (t ushr 32).toInt()
        if (last) v[14] = v[14].inv()
        fun g(a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            v[a] = v[a] + v[b] + x; v[d] = Integer.rotateRight(v[d] xor v[a], 16)
            v[c] = v[c] + v[d]; v[b] = Integer.rotateRight(v[b] xor v[c], 12)
            v[a] = v[a] + v[b] + y; v[d] = Integer.rotateRight(v[d] xor v[a], 8)
            v[c] = v[c] + v[d]; v[b] = Integer.rotateRight(v[b] xor v[c], 7)
        }
        for (r in 0 until 10) {
            val s = SIGMA[r]
            g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    /** BLAKE2s با خروجی outLen بایت و کلید اختیاری (حداکثر ۳۲ بایت) */
    fun blake2s(data: ByteArray, outLen: Int = 32, key: ByteArray = ByteArray(0)): ByteArray {
        require(outLen in 1..32 && key.size <= 32)
        val h = IV.copyOf()
        h[0] = h[0] xor (0x01010000 or (key.size shl 8) or outLen)
        val input = if (key.isEmpty()) data else ByteArray(64).also { key.copyInto(it) } + data
        var t = 0L
        var off = 0
        if (input.isEmpty()) {
            compress(h, ByteArray(64), 0, true)
        } else {
            while (input.size - off > 64) {
                t += 64
                compress(h, input.copyOfRange(off, off + 64), t, false)
                off += 64
            }
            val rest = input.size - off
            t += rest
            compress(h, ByteArray(64).also { input.copyInto(it, 0, off, input.size) }, t, true)
        }
        val out = ByteArray(32)
        for (i in 0 until 8) wr32(out, i * 4, h[i])
        return out.copyOf(outLen)
    }

    fun hash(vararg parts: ByteArray): ByteArray = blake2s(parts.fold(ByteArray(0)) { a, b -> a + b })

    /** HMAC با BLAKE2s (بلوک ۶۴ بایت) */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val k = (if (key.size > 64) blake2s(key) else key).copyOf(64)
        val ipad = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        return blake2s(opad + blake2s(ipad + data))
    }

    // ------------------------------------------------------------------ ChaCha20-Poly1305
    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = rd32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = rd32(nonce, i * 4)
        val x = s.copyOf()
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) wr32(out, i * 4, x[i] + s[i])
        return out
    }

    private fun chachaXor(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var c = counter
        var off = 0
        while (off < data.size) {
            val ks = chachaBlock(key, c++, nonce)
            for (i in 0 until minOf(64, data.size - off)) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += 64
        }
        return out
    }

    private val P1305: BigInteger = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val M128: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        val rb = key.copyOfRange(0, 16)
        for (i in intArrayOf(3, 7, 11, 15)) rb[i] = (rb[i].toInt() and 15).toByte()
        for (i in intArrayOf(4, 8, 12)) rb[i] = (rb[i].toInt() and 252).toByte()
        val r = le(rb)
        val s = le(key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var off = 0
        while (off < msg.size) {
            val n = minOf(16, msg.size - off)
            val block = msg.copyOfRange(off, off + n) + byteArrayOf(1)
            acc = acc.add(le(block)).multiply(r).mod(P1305)
            off += 16
        }
        val tag = acc.add(s).and(M128)
        val be = tag.toByteArray()
        val out = ByteArray(16)
        for (i in 0 until minOf(16, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun pad16(n: Int) = ByteArray((16 - n % 16) % 16)
    private fun le64(n: Long) = ByteArray(8) { (n ushr (8 * it)).toByte() }

    private fun macData(aad: ByteArray, ct: ByteArray) =
        aad + pad16(aad.size) + ct + pad16(ct.size) + le64(aad.size.toLong()) + le64(ct.size.toLong())

    /** رمز و برچسب (ciphertext || tag) */
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val polyKey = chachaBlock(key, 0, nonce).copyOf(32)
        val ct = chachaXor(key, 1, nonce, plain)
        return ct + poly1305(polyKey, macData(aad, ct))
    }

    /** متن اصلی، یا null اگر برچسب درست نبود */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val ct = sealed.copyOf(sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        val polyKey = chachaBlock(key, 0, nonce).copyOf(32)
        if (!java.security.MessageDigest.isEqual(tag, poly1305(polyKey, macData(aad, ct)))) return null
        return chachaXor(key, 1, nonce, ct)
    }

    /** nonce وایرگارد: ۴ بایت صفر و شمارنده‌ی ۸ بایتی little-endian */
    fun wgNonce(counter: Long) = ByteArray(4) + le64(counter)
}
