package app.remotesupport

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Connect screen: address + ID + password of the computer you want to help. */
class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var userId: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var recentBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "RemoteSupport"; textSize = 28f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Connect to a computer that is sharing its screen with RemoteSupport."
            textSize = 14f; setTextColor(Palette.MUTED); setPadding(0, dp(4), 0, dp(20))
        })

        address = field("Address  (e.g. 100.64.0.5 or 192.168.1.20:8765)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        userId = field("ID  (9 digits)", InputType.TYPE_CLASS_NUMBER)
        password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.imeOptions = EditorInfo.IME_ACTION_GO
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
        listOf(address, userId, password).forEach { col.addView(it, lp(top = 10)) }

        val go = styledButton("Connect", Palette.BLUE, 16f).apply { setOnClickListener { connect() } }
        col.addView(go, lp(top = 18, height = dp(50)))

        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(14), 0, 0) }
        col.addView(status)

        col.addView(TextView(this).apply {
            text = "RECENT"; textSize = 12f; setTextColor(Palette.MUTED); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(26), 0, dp(6))
        })
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentBox)

        col.addView(TextView(this).apply {
            text = "The person at the computer sees a request and must tap Allow. " +
                "Use only on computers whose owner has agreed to the session."
            textSize = 12f; setTextColor(Palette.MUTED); setPadding(0, dp(26), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        val msg = Flash.message
        if (msg != null) {
            status.setTextColor(if (Flash.isError) Color.parseColor("#f87171") else Palette.MUTED)
            status.text = msg
            Flash.message = null
        }
        password.setText("")
        renderRecents()
    }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#64748b"))
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        roundedBg(Palette.FIELD, 10)
    }

    private fun lp(top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun renderRecents() {
        recentBox.removeAllViews()
        val items = Recents.load(this)
        if (items.isEmpty()) {
            recentBox.addView(TextView(this).apply {
                text = "Nothing yet. Successful connections are remembered here (never passwords)."
                textSize = 13f; setTextColor(Palette.MUTED)
            })
            return
        }
        for (item in items) {
            recentBox.addView(TextView(this).apply {
                text = "${item.address}   ·   ID ${item.id}"
                textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                roundedBg(Palette.FIELD, 10)
                setOnClickListener {
                    address.setText(item.address); userId.setText(item.id); password.requestFocus()
                }
            }, lp(top = 6))
        }
    }

    private fun connect() {
        val addr = address.text.toString().trim()
        val id = userId.text.toString().trim()
        val pw = password.text.toString()
        val err = when {
            addr.isEmpty() -> "Enter the computer's address."
            id.isEmpty() -> "Enter the ID shown on the other computer."
            pw.isEmpty() -> "Enter the password shown on the other computer."
            else -> null
        }
        if (err != null) {
            status.setTextColor(Color.parseColor("#f87171")); status.text = err; return
        }
        status.text = ""
        Pending.url = normalizeAddress(addr)
        Pending.address = addr
        Pending.user = id
        Pending.password = pw
        startActivity(Intent(this, RemoteActivity::class.java))
    }
}
