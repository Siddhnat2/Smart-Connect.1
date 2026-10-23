package app.remotesupport

import kotlin.math.min

/**
 * Turns soft-keyboard text edits into key presses on the PC. Pure Kotlin (unit-testable).
 *
 * Keyboards such as Gboard send text as a "composing" word that they keep rewriting
 * (h -> he -> hel -> hello -> "hello " committed, or auto-corrected). Instead of ignoring the
 * composing text (typing would then appear a whole word late, or not at all), every rewrite is
 * applied to the PC as the minimal edit: Backspace for the changed tail, then the new characters.
 */
class TextTyper(private val sink: Sink) {

    interface Sink {
        /** Press and release one key, using the key names the PC understands ("a", "space", "Return", "BackSpace"). */
        fun key(name: String)
    }

    private var composing = ""

    /** The keyboard (re)writes the word it is composing. */
    fun setComposing(text: String) {
        replaceComposing(text)
        composing = text
    }

    /** The keyboard commits [text] in place of the composing word. */
    fun commit(text: String) {
        replaceComposing(text)
        composing = ""
    }

    fun finish() {
        composing = ""
    }

    /** Keyboard deleted [before] characters in front of the cursor (committed text or the composing word). */
    fun deleteBefore(before: Int) {
        if (before <= 0) return
        composing = composing.dropLast(min(before, composing.length))
        repeat(before) { sink.key("BackSpace") }
    }

    /** Type text directly (clipboard "Type clip", hardware-less paste). */
    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            keyFor(cp)?.let { sink.key(it) }
        }
    }

    private fun replaceComposing(new: String) {
        var common = 0
        val max = min(composing.length, new.length)
        while (common < max && composing[common] == new[common]) common++
        repeat(composing.length - common) { sink.key("BackSpace") }
        type(new.substring(common))
    }

    companion object {
        /** Key name for a character, or null if it cannot be typed (control characters, emoji). */
        fun keyFor(cp: Int): String? = when {
            cp == '\n'.code -> "Return"
            cp == '\t'.code -> "Tab"
            cp == ' '.code -> "space"
            cp < 32 || cp == 127 -> null
            cp > 0xFFFF -> null                      // outside the BMP: the PC cannot type it as one key
            else -> cp.toChar().toString()
        }
    }
}
