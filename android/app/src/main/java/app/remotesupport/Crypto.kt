package app.remotesupport

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoException(msg: String) : Exception(msg)

/**
 * Kotlin port of rsupport/crypto.py. Byte-for-byte compatible with the desktop app:
 *   key     = PBKDF2-HMAC-SHA256(user \0 password, salt, iters)
 *   proof   = HMAC(key, "rs1|<role>|<user>|" hn "|" vn)
 *   session = HKDF-SHA256(key, salt = hn|vn, info = "rs1-session-keys") -> 2 x AES-256-GCM keys
 */
object Crypto {
    const val MIN_ITERS = 100_000
    const val MAX_ITERS = 5_000_000

    private fun newMac(key: ByteArray): Mac =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = newMac(key)
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** PBKDF2-HMAC-SHA256, 32-byte output (= exactly one block, so no block loop is needed). */
    fun deriveKey(username: String, password: String, salt: ByteArray, iters: Int): ByteArray {
        val pw = username.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + password.toByteArray(Charsets.UTF_8)
        val mac = newMac(pw)
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iters) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    fun makeProof(key: ByteArray, role: String, username: String, hn: ByteArray, vn: ByteArray): ByteArray {
        val bar = byteArrayOf('|'.code.toByte())
        return hmac(
            key,
            "rs1|$role|".toByteArray(Charsets.UTF_8),
            username.toByteArray(Charsets.UTF_8), bar, hn, bar, vn
        )
    }

    fun ctEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** HKDF-SHA256 (RFC 5869), 64 bytes of output. */
    fun hkdf64(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        val t1 = hmac(prk, info, byteArrayOf(1))
        val t2 = hmac(prk, t1, info, byteArrayOf(2))
        return t1 + t2
    }
}

/** AES-256-GCM channel with implicit 64-bit counter nonces (replay / reorder / tamper detection). */
class SecureChannel(key: ByteArray, hn: ByteArray, vn: ByteArray, isHost: Boolean) {
    private val txKey: SecretKeySpec
    private val rxKey: SecretKeySpec
    private var txCtr = 0L
    private var rxCtr = 0L

    init {
        val okm = Crypto.hkdf64(key, hn + vn, "rs1-session-keys".toByteArray(Charsets.UTF_8))
        val hostToViewer = okm.copyOfRange(0, 32)
        val viewerToHost = okm.copyOfRange(32, 64)
        txKey = SecretKeySpec(if (isHost) hostToViewer else viewerToHost, "AES")
        rxKey = SecretKeySpec(if (isHost) viewerToHost else hostToViewer, "AES")
    }

    private fun nonce(ctr: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(ctr).array()

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, txKey, GCMParameterSpec(128, nonce(txCtr)))
        val out = c.doFinal(plaintext)
        txCtr++
        return out
    }

    @Synchronized
    fun open(ciphertext: ByteArray): ByteArray {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, rxKey, GCMParameterSpec(128, nonce(rxCtr)))
            val pt = c.doFinal(ciphertext)
            rxCtr++
            return pt
        } catch (e: GeneralSecurityException) {
            throw CryptoException("bad frame (tampered, replayed or out of order)")
        }
    }
}
