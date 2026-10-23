package app.remotesupport

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import org.json.JSONArray
import org.json.JSONObject

object Palette {
    val BG = Color.parseColor("#0b1220")
    val BAR = Color.parseColor("#111a2e")
    val FIELD = Color.parseColor("#17233b")
    val BLUE = Color.parseColor("#2563eb")
    val RED = Color.parseColor("#dc2626")
    val GREEN = Color.parseColor("#22c55e")
    val MUTED = Color.parseColor("#94a3b8")
    val KEY = Color.parseColor("#1e2b47")
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

fun View.roundedBg(color: Int, radiusDp: Int) {
    background = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }
}

fun Context.styledButton(text: String, color: Int, sizeSp: Float = 14f): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextColor(Color.WHITE)
    textSize = sizeSp
    gravity = Gravity.CENTER
    stateListAnimator = null
    minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
    setPadding(dp(10), dp(8), dp(10), dp(8))
    roundedBg(color, 10)
    isFocusable = false
}

/** Hands the connection details from the connect screen to the session screen (kept in memory only). */
object Pending {
    var url: String = ""
    var address: String = ""
    var user: String = ""
    var password: String = ""
    fun clear() { url = ""; address = ""; user = ""; password = "" }
}

/** One-shot message for the connect screen (why the last session ended). */
object Flash {
    var message: String? = null
    var isError: Boolean = false
}

/** Recent connections. Passwords are never stored. */
object Recents {
    private const val PREFS = "rs"
    private const val KEY = "recent"

    data class Item(val address: String, val id: String)

    fun load(ctx: Context): List<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Item(o.getString("a"), o.getString("i")) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, address: String, id: String) {
        val list = (listOf(Item(address, id)) + load(ctx).filter { it.address != address || it.id != id }).take(8)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("a", it.address).put("i", it.id)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
