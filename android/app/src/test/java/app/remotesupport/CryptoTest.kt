package app.remotesupport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test vectors were produced by the desktop app's own rsupport/crypto.py, so a pass here
 * proves the Android app can authenticate against and decrypt traffic from the PC app.
 */
class CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val salt = ByteArray(16) { it.toByte() }
    private val hn = ByteArray(16) { (16 + it).toByte() }
    private val vn = ByteArray(16) { (32 + it).toByte() }
    private val user = "123456789"
    private val key get() = Crypto.deriveKey(user, "Passw0rdXyz2", salt, 1000)

    @Test fun pbkdf2MatchesPython() {
        assertEquals("2d6cf0d4e00bdaa9daf9eaed030ba97b09d2076754e139521f169dc9ab701210", key.hex())
    }

    @Test fun proofsMatchPython() {
        assertEquals("09ad40a3e08e1043ce3fc06dce9d7e51af665a2256119b586caaa67f114acc0c",
            Crypto.makeProof(key, "viewer", user, hn, vn).hex())
        assertEquals("315fbed84c40a245530a852110645278ce03cdf12113a15fc22b947c34244787",
            Crypto.makeProof(key, "host", user, hn, vn).hex())
    }

    @Test fun decryptsHostTrafficAndEncryptsLikePython() {
        val ch = SecureChannel(key, hn, vn, isHost = false)
        assertArrayEquals(byteArrayOf(1) + "hello".toByteArray(),
            ch.open(hex("35d0826091969caa4f9b12a02a291bed8c7ee375661a")))
        assertArrayEquals(byteArrayOf(3) + "{\"t\":\"accepted\"}".toByteArray(),
            ch.open(hex("e863c9dda6ab73927cac4de2f7d79490531121ae1eb89a6835e3fb9574de2d944b")))
        assertEquals("f52d000f59e50856ae23af638edbe8011e2cfe254bdb7e30160577bda2",
            ch.seal(byteArrayOf(2) + "{\"t\":\"move\"}".toByteArray()).hex())
    }

    @Test fun replayAndTamperAreRejected() {
        val ch = SecureChannel(key, hn, vn, isHost = false)
        val first = hex("35d0826091969caa4f9b12a02a291bed8c7ee375661a")
        ch.open(first)
        try { ch.open(first); fail("replay accepted") } catch (_: CryptoException) {}
        val bad = hex("e863c9dda6ab73927cac4de2f7d79490531121ae1eb89a6835e3fb9574de2d944b")
        bad[3] = (bad[3].toInt() xor 1).toByte()
        try { ch.open(bad); fail("tampered frame accepted") } catch (_: CryptoException) {}
    }

    @Test fun constantTimeCompare() {
        assertTrue(Crypto.ctEqual(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(Crypto.ctEqual(byteArrayOf(1, 2), byteArrayOf(1, 3)))
    }
}
