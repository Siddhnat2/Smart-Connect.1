package app.remotesupport

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

interface RemoteListener {
    /** Called from a background thread. */
    fun onStatus(text: String)
    fun onAccepted()
    fun onFrame(sw: Int, sh: Int, x: Int, y: Int, w: Int, h: Int, jpeg: ByteArray)
    fun onClosed(reason: String, isError: Boolean)
}

/** "1.2.3.4" -> "ws://1.2.3.4:8765", "1.2.3.4:9000" -> "ws://1.2.3.4:9000" (same as the PC app). */
fun normalizeAddress(input: String, defaultPort: Int = 8765): String {
    var addr = input.trim()
    var scheme = ""
    if ("://" in addr) {
        scheme = addr.substringBefore("://") + "://"
        addr = addr.substringAfter("://")
    }
    if (scheme.isEmpty()) scheme = "ws://"
    val hostPart = addr.substringBefore("/")
    if (!(hostPart.startsWith("[") || ":" in hostPart)) {
        addr = hostPart + ":$defaultPort" + addr.substring(hostPart.length)
    }
    return scheme + addr
}

/**
 * Technician-side client. Speaks the same direct-mode protocol as rsupport/viewer.py:
 * challenge -> auth (mutual proof) -> encrypted "accepted" -> encrypted frames / input.
 * All protocol messages are BINARY websocket frames.
 */
class RemoteClient(
    private val url: String,
    private val user: String,
    private val password: String,
    private val listener: RemoteListener,
) {
    private enum class Stage { CONNECTING, CHALLENGE, AUTH, CONSENT, ACTIVE, CLOSED }

    @Volatile private var stage = Stage.CONNECTING
    @Volatile private var ws: WebSocket? = null
    @Volatile private var chan: SecureChannel? = null
    @Volatile private var userClosed = false
    private var finished = false

    private var key = ByteArray(0)
    private var hn = ByteArray(0)
    private var vn = ByteArray(0)

    // One thread seals + sends, so the implicit AES-GCM nonce counter always matches wire order.
    private val sender = Executors.newSingleThreadExecutor()

    fun connect() {
        try {
            val req = Request.Builder().url(url).build()
            ws = http.newWebSocket(req, Handler())
            listener.onStatus("Connecting…")
        } catch (e: IllegalArgumentException) {
            finish("That address is not valid.", true)
        }
    }

    // ---- sending -------------------------------------------------------------------------
    fun move(x: Float, y: Float) = sendInput(JSONObject().put("t", "move").put("x", x.toDouble()).put("y", y.toDouble()))

    fun button(b: String, down: Boolean, x: Float, y: Float) =
        sendInput(JSONObject().put("t", "btn").put("b", b).put("d", down).put("x", x.toDouble()).put("y", y.toDouble()))

    fun scroll(dx: Int, dy: Int) = sendInput(JSONObject().put("t", "scroll").put("dx", dx).put("dy", dy))

    fun key(name: String, down: Boolean) = sendInput(JSONObject().put("t", "key").put("k", name).put("d", down))

    private fun sendInput(obj: JSONObject) {
        if (stage != Stage.ACTIVE) return
        enqueue(T_INPUT, obj)
    }

    private fun enqueue(type: Int, obj: JSONObject) {
        try {
            sender.execute {
                val c = chan ?: return@execute
                val ct = c.seal(byteArrayOf(type.toByte()) + obj.toString().toByteArray(Charsets.UTF_8))
                ws?.send(ct.toByteString())
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** User pressed Disconnect / left the screen. */
    fun close() {
        userClosed = true
        finish("Disconnected.", false)
    }

    private fun finish(reason: String, isError: Boolean) {
        synchronized(this) {
            if (finished) return
            finished = true
        }
        val wasActive = stage == Stage.ACTIVE
        stage = Stage.CLOSED
        try {
            if (wasActive) enqueue(T_CTRL, JSONObject().put("t", "bye"))
            sender.execute { ws?.close(1000, "bye") }
            sender.shutdown()
        } catch (_: RejectedExecutionException) {
            ws?.close(1000, "bye")
        }
        if (!userClosed) listener.onClosed(reason, isError)
    }

    // ---- receiving -----------------------------------------------------------------------
    private inner class Handler : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            stage = Stage.CHALLENGE
            listener.onStatus("Connected - authenticating…")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                handle(bytes.toByteArray())
            } catch (e: CryptoException) {
                finish("Security error: a corrupted frame was received.", true)
            } catch (e: Exception) {
                finish("Malformed response from the computer (${e.javaClass.simpleName}).", true)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            when {
                reason == "busy" -> finish("That computer already has a technician connected.", true)
                stage == Stage.ACTIVE -> finish("The session was ended by the other side.", false)
                else -> finish("Connection closed during setup.", true)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stage == Stage.CONNECTING) {
                finish(
                    "Could not reach that address. Check it is correct, that RemoteSupport is open and " +
                        "sharing on the other computer, and that you are on the same network or VPN " +
                        "(for example Tailscale).", true
                )
            } else {
                finish(if (stage == Stage.ACTIVE) "Connection lost." else "Connection closed during setup.", true)
            }
        }
    }

    private fun handle(data: ByteArray) {
        when (stage) {
            Stage.CHALLENGE -> {
                val m = JSONObject(String(data, Charsets.UTF_8))
                if (m.optString("t") == "error") {
                    finish(
                        if (m.optString("code") == "locked") "The computer is temporarily locked after failed attempts."
                        else "Host error: ${m.optString("code")}", true
                    )
                    return
                }
                val iters = m.getInt("iters")
                if (iters < Crypto.MIN_ITERS || iters > Crypto.MAX_ITERS) {
                    finish("Host sent unsafe key-derivation parameters.", true); return
                }
                val salt = b64d(m.getString("salt"))
                hn = b64d(m.getString("hn"))
                if (hn.size != 16) { finish("Malformed challenge.", true); return }
                listener.onStatus("Verifying password…")
                key = Crypto.deriveKey(user, password, salt, iters)
                vn = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val auth = JSONObject()
                    .put("t", "auth").put("user", user).put("vn", b64e(vn))
                    .put("proof", b64e(Crypto.makeProof(key, "viewer", user, hn, vn)))
                stage = Stage.AUTH
                ws?.send(auth.toString().toByteArray(Charsets.UTF_8).toByteString())
            }

            Stage.AUTH -> {
                val m = JSONObject(String(data, Charsets.UTF_8))
                when (m.optString("t")) {
                    "auth_fail" -> finish("Wrong ID or password.", true)
                    "auth_ok" -> {
                        val expect = Crypto.makeProof(key, "host", user, hn, vn)
                        if (!Crypto.ctEqual(b64d(m.getString("proof")), expect)) {
                            finish("The other side could not prove it knows the password.", true); return
                        }
                        chan = SecureChannel(key, hn, vn, isHost = false)
                        stage = Stage.CONSENT
                        listener.onStatus("Waiting for the person at the computer to click Allow…")
                    }
                    else -> finish("Unexpected reply: ${m.optString("t")}", true)
                }
            }

            Stage.CONSENT -> {
                val pt = chan!!.open(data)
                val msg = JSONObject(String(pt, 1, pt.size - 1, Charsets.UTF_8))
                when (msg.optString("t")) {
                    "accepted" -> { stage = Stage.ACTIVE; listener.onAccepted() }
                    "denied" -> finish("The person at the computer did not allow the connection.", true)
                    else -> finish("Unexpected reply while waiting for permission.", true)
                }
            }

            Stage.ACTIVE -> {
                val pt = chan!!.open(data)
                if (pt.isEmpty()) return
                when (pt[0].toInt()) {
                    T_FRAME -> {
                        if (pt.size <= 1 + FRAME_HDR) return
                        val bb = ByteBuffer.wrap(pt, 1, FRAME_HDR)          // big-endian, ">HHHHHH"
                        val sw = bb.short.toInt() and 0xFFFF
                        val sh = bb.short.toInt() and 0xFFFF
                        val x = bb.short.toInt() and 0xFFFF
                        val y = bb.short.toInt() and 0xFFFF
                        val w = bb.short.toInt() and 0xFFFF
                        val h = bb.short.toInt() and 0xFFFF
                        listener.onFrame(sw, sh, x, y, w, h, pt.copyOfRange(1 + FRAME_HDR, pt.size))
                    }
                    T_CTRL -> {
                        val msg = JSONObject(String(pt, 1, pt.size - 1, Charsets.UTF_8))
                        if (msg.optString("t") == "bye") finish("The session was ended by the other side.", false)
                    }
                }
            }

            else -> {}
        }
    }

    private fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    companion object {
        private const val T_FRAME = 1
        private const val T_INPUT = 2
        private const val T_CTRL = 3
        private const val FRAME_HDR = 12

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // the session is long-lived; pings detect dead links
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
