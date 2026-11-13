package vn.tutu.muser1

import java.security.SecureRandom

/**
 * Pure-Kotlin RFC 7748 X25519 Diffie-Hellman (Curve25519 Montgomery ladder).
 * Android 5.1.1 (API 22) does not ship JCA X25519/XDH, so we implement it directly.
 */
object Rfc7748X25519 {

    const val KEY_BYTES = 32

    private val BASEPOINT_U = ByteArray(KEY_BYTES).apply { this[0] = 9 }

    private val CURVE_CONST_121665 = LongArray(16).apply {
        this[0] = 0xdb41L
        this[1] = 1L
    }

    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    )

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val raw = ByteArray(KEY_BYTES)
        random.nextBytes(raw)
        val priv = clampScalar(raw)
        val pub = scalarMultBase(priv)
        return KeyPair(priv, pub)
    }

    fun clampScalar(scalar: ByteArray): ByteArray {
        require(scalar.size == KEY_BYTES) { "X25519 scalar must be 32 bytes" }
        return scalar.copyOf().also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = (it[31].toInt() and 127).toByte()
            it[31] = (it[31].toInt() or 64).toByte()
        }
    }

    fun scalarMultBase(scalar: ByteArray): ByteArray = scalarMult(scalar, BASEPOINT_U)

    /**
     * Computes X25519(scalar, uCoordinate). Clamps a copy of [scalar] and masks bit 255
     * of [uCoordinate] per RFC 7748 Section 5.
     */
    fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        require(scalar.size == KEY_BYTES) { "X25519 scalar must be 32 bytes" }
        require(uCoordinate.size == KEY_BYTES) { "X25519 point must be 32 bytes" }

        val z = clampScalar(scalar)
        val x = LongArray(16)
        unpack25519(x, uCoordinate)

        val a = LongArray(16).apply { this[0] = 1L }
        val b = x.copyOf()
        val c = LongArray(16)
        val d = LongArray(16).apply { this[0] = 1L }
        val e = LongArray(16)
        val f = LongArray(16)

        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            sel25519(a, b, r)
            sel25519(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            square(d, e)
            square(f, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            square(b, a)
            sub(c, d, f)
            mul(a, c, CURVE_CONST_121665)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            square(b, e)
            sel25519(a, b, r)
            sel25519(c, d, r)
        }

        inv25519(c, c)
        mul(a, a, c)
        val out = ByteArray(KEY_BYTES)
        pack25519(out, a)
        return out
    }

    fun isAllZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) {
            acc = acc or (b.toInt() and 0xff)
        }
        return acc == 0
    }

    private fun unpack25519(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) {
            o[i] = ((n[2 * i].toInt() and 0xff) or ((n[2 * i + 1].toInt() and 0xff) shl 8)).toLong()
        }
        o[15] = o[15] and 0x7fffL
    }

    private fun car25519(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            val next = if (i < 15) i + 1 else 0
            val mult = if (i == 15) 38L else 1L
            o[next] += (c - 1L) * mult
            o[i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, b: Int) {
        val c = -(b.toLong() and 1L)
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) {
            for (j in 0 until 16) {
                t[i + j] += a[i] * b[j]
            }
        }
        for (i in 0 until 15) {
            t[i] += 38L * t[i + 16]
        }
        for (i in 0 until 16) o[i] = t[i]
        car25519(o)
        car25519(o)
    }

    private fun square(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv25519(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            square(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val t = n.copyOf()
        car25519(t)
        car25519(t)
        car25519(t)
        val m = LongArray(16)
        repeat(2) {
            m[0] = t[0] - 0xffedL
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffffL - ((m[i - 1] shr 16) and 1L)
                m[i - 1] = m[i - 1] and 0xffffL
            }
            m[15] = t[15] - 0x7fffL - ((m[14] shr 16) and 1L)
            val b = ((m[15] shr 16) and 1L).toInt()
            m[14] = m[14] and 0xffffL
            sel25519(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xffL).toByte()
            o[2 * i + 1] = ((t[i] ushr 8) and 0xffL).toByte()
        }
    }
}

