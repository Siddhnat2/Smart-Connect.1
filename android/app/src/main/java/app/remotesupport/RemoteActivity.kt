package app.remotesupport

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Owns one session: connects, shows the overlay while waiting for "Allow", then the live screen. */
class RemoteActivity : Activity(), RemoteListener {
    private val ui = Handler(Looper.getMainLooper())
    private var client: RemoteClient? = null
    private var destroyed = false
    private var address = ""
    private var userId = ""

    private lateinit var remote: RemoteView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private lateinit var stats: TextView
    private lateinit var keyBar: View
    private lateinit var dragBtn: Button
    private lateinit var padBtn: Button
    private lateinit var speedBtn: Button
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 3f, 4f)
    private var speedIdx = 2
    private val stickyButtons = LinkedHashMap<String, Button>()
    private var lastFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Palette.BAR
        window.navigationBarColor = Palette.BG

        val url = Pending.url
        val user = Pending.user
        val pw = Pending.password
        address = Pending.address
        userId = user
        Pending.clear()
        if (url.isEmpty() || user.isEmpty()) {          // process was recreated: nothing to resume
            finish(); return
        }

        buildUi()
        val c = RemoteClient(url, user, pw, this)
        client = c
        remote.input = object : RemoteView.Input {
            override fun move(x: Float, y: Float) = c.move(x, y)
            override fun button(b: String, down: Boolean, x: Float, y: Float) = c.button(b, down, x, y)
            override fun scroll(dx: Int, dy: Int) = c.scroll(dx, dy)
            override fun key(name: String, down: Boolean) = c.key(name, down)
        }
        c.connect()
    }

    // ---- UI ----------------------------------------------------------------------------------
    private fun buildUi() {
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val keysBtn = styledButton("Keyboard", Palette.KEY, 12f)
        padBtn = styledButton("Pad: blank", Palette.KEY, 12f)
        dragBtn = styledButton("Drag: off", Palette.KEY, 12f)
        val fitBtn = styledButton("Fit", Palette.KEY, 12f)
        val endBtn = styledButton("Disconnect", Palette.RED, 12f)
        for (b in listOf(keysBtn, padBtn, dragBtn, fitBtn, endBtn)) {
            b.minimumWidth = dp(64)
            barRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        // Scrollable, so five buttons still fit (and stay readable) on narrow phones.
        val bar = HorizontalScrollView(this).apply {
            setBackgroundColor(Palette.BAR)
            isHorizontalScrollBarEnabled = false
            addView(barRow)
        }
        main.addView(bar)

        stats = TextView(this).apply {
            textSize = 11f; setTextColor(Palette.MUTED); setBackgroundColor(Palette.BAR)
            setPadding(dp(12), 0, dp(12), dp(4)); text = ""
        }
        main.addView(stats)

        remote = RemoteView(this)
        main.addView(remote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        main.addView(buildClickBar())

        keyBar = buildKeyBar().apply { visibility = View.GONE }
        main.addView(keyBar)

        remote.onStickyChanged = { ui.post { refreshSticky() } }

        keysBtn.setOnClickListener {
            val show = keyBar.visibility != View.VISIBLE
            keyBar.visibility = if (show) View.VISIBLE else View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (show) {
                remote.requestFocus()
                imm.restartInput(remote)
                // post: the view must be laid out and focused first, otherwise many phones silently ignore the request
                remote.post { imm.showSoftInput(remote, 0) }
            } else {
                imm.hideSoftInputFromWindow(remote.windowToken, 0)
            }
        }
        padBtn.setOnClickListener {
            remote.padAll = !remote.padAll
            padBtn.text = if (remote.padAll) "Pad: full" else "Pad: blank"
            padBtn.roundedBg(if (remote.padAll) Palette.BLUE else Palette.KEY, 10)
        }
        dragBtn.setOnClickListener {
            remote.dragMode = !remote.dragMode
            dragBtn.text = if (remote.dragMode) "Drag: ON" else "Drag: off"
            dragBtn.roundedBg(if (remote.dragMode) Palette.BLUE else Palette.KEY, 10)
        }
        fitBtn.setOnClickListener { remote.resetZoom() }
        endBtn.setOnClickListener { finish() }

        // "Connecting…" overlay, shown until the person at the computer taps Allow.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.BG)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            isClickable = true
        }
        box.addView(ProgressBar(this))
        overlayText = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(22)); text = "Connecting…"
        }
        box.addView(overlayText)
        box.addView(styledButton("Cancel", Palette.KEY, 14f).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay = box

        setContentView(FrameLayout(this).apply {
            addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }

    /** Left / Right click buttons under the screen (press and hold = hold the button, for dragging) and speed. */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildClickBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.BAR)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        fun clickButton(label: String, name: String): Button =
            styledButton(label, Palette.KEY, 14f).apply {
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            v.roundedBg(Palette.BLUE, 10)
                            remote.pressButton(name, true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            v.roundedBg(Palette.KEY, 10)
                            remote.pressButton(name, false)
                        }
                    }
                    true
                }
            }
        val lp = { w: Float -> LinearLayout.LayoutParams(0, dp(44), w).apply { marginStart = dp(3); marginEnd = dp(3) } }
        row.addView(clickButton("Left click", "left"), lp(2f))
        row.addView(clickButton("Right click", "right"), lp(2f))
        speedBtn = styledButton("Speed 2x", Palette.KEY, 12f).apply {
            setOnClickListener {
                speedIdx = (speedIdx + 1) % speeds.size
                remote.padGain = speeds[speedIdx]
                text = "Speed " + (if (speeds[speedIdx] % 1f == 0f) speeds[speedIdx].toInt().toString() else speeds[speedIdx].toString()) + "x"
            }
        }
        row.addView(speedBtn, lp(1.4f))
        remote.padGain = speeds[speedIdx]
        return row
    }

    private fun buildKeyBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun add(label: String, onClick: () -> Unit): Button {
            val b = styledButton(label, Palette.KEY, 13f).apply { setOnClickListener { onClick() } }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
            return b
        }
        fun tap(label: String, key: String) = add(label) { remote.tapKey(key) }
        fun mod(label: String, key: String) { stickyButtons[key] = add(label) { remote.toggleSticky(key) } }

        fun chord(label: String, key: String) = add(label) { remote.chord("Control_L", key) }

        tap("Esc", "Escape"); tap("Tab", "Tab"); tap("Enter", "Return"); tap("⌫", "BackSpace")
        mod("Ctrl", "Control_L"); mod("Alt", "Alt_L"); mod("Shift", "Shift_L"); mod("Win", "Super_L")
        tap("←", "Left"); tap("↑", "Up"); tap("↓", "Down"); tap("→", "Right")
        tap("Home", "Home"); tap("End", "End"); tap("PgUp", "Prior"); tap("PgDn", "Next")
        tap("Del", "Delete"); tap("F5", "F5"); tap("F11", "F11")
        chord("Copy", "c"); chord("Paste", "v"); chord("Cut", "x"); chord("All", "a"); chord("Undo", "z")
        add("Type clip") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            if (!text.isNullOrEmpty()) remote.typeText(text)
        }

        return HorizontalScrollView(this).apply {
            setBackgroundColor(Palette.BAR)
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun refreshSticky() {
        for ((key, btn) in stickyButtons) btn.roundedBg(if (key in remote.sticky) Palette.BLUE else Palette.KEY, 10)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val n = remote.frames
            val size = remote.frameSize?.let { "${it.first}×${it.second}   " } ?: ""
            stats.text = "● Connected   $size${n - lastFrames} fps"
            stats.setTextColor(Palette.GREEN)
            lastFrames = n
            ui.postDelayed(this, 1000)
        }
    }

    // ---- RemoteListener (called on the network thread) ---------------------------------------------
    override fun onStatus(text: String) {
        ui.post { if (!destroyed) overlayText.text = text }
    }

    override fun onAccepted() {
        ui.post {
            if (destroyed) return@post
            overlay.visibility = View.GONE
            Recents.add(this, address, userId)
            remote.requestFocus()
            ui.post(ticker)
        }
    }

    override fun onFrame(sw: Int, sh: Int, x: Int, y: Int, w: Int, h: Int, jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return   // decode off the UI thread
        ui.post {
            if (destroyed) bmp.recycle() else remote.applyFrame(sw, sh, x, y, bmp)
        }
    }

    override fun onClosed(reason: String, isError: Boolean) {
        ui.post {
            if (destroyed) return@post
            Flash.message = reason
            Flash.isError = isError
            finish()
        }
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        if (::remote.isInitialized) remote.releaseAll()
        client?.close()
        super.onDestroy()
    }
}
