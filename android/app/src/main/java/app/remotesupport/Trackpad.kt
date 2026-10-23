package app.remotesupport

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Laptop-style touchpad logic, kept free of any Android classes so it can be unit-tested on the JVM.
 *
 * The phone keeps a *virtual cursor* in normalised 0..1 remote-screen coordinates. Finger movement
 * moves that cursor RELATIVELY (with speed-based acceleration); every change is sent to the PC as an
 * absolute `move`, so the PC side needs no protocol change.
 *
 *   one finger, move ........ move the pointer
 *   one finger, tap ......... left click          (two quick taps = double click)
 *   tap, then touch + move .. hold the left button and drag (move windows, select text)
 *   two fingers, tap ........ right click
 *   two fingers, move ....... scroll
 */
class Trackpad(private val out: Out, val cfg: Config = Config()) {

    interface Out {
        fun move(nx: Float, ny: Float)
        fun button(name: String, down: Boolean, nx: Float, ny: Float)
        fun scroll(dx: Int, dy: Int)
    }

    class Config(
        /** Base finger-to-pointer multiplier (1 finger px -> `gain` px on the displayed remote screen). */
        var gain: Float = 2.0f,
        /** Extra multiplier per px/ms of finger speed (fast flicks cross the screen, slow moves are precise). */
        val accel: Float = 0.6f,
        val accelMax: Float = 2.0f,
        val slopPx: Float = 10f,
        val tapMs: Long = 250,
        val twoFingerTapMs: Long = 350,
        val doubleTapMs: Long = 300,
        /** Finger travel (px) per scroll notch. */
        val scrollStepPx: Float = 40f,
    )

    /** Virtual cursor, normalised 0..1. */
    var nx = 0.5f
        private set
    var ny = 0.5f
        private set

    /** Size in px of the remote screen as drawn on the phone; converts finger px to normalised units. */
    var areaW = 1f
    var areaH = 1f

    /** True while the left button is held by a tap-and-drag. */
    var dragging = false
        private set

    private enum class S { IDLE, ONE, TWO, IGNORE }

    private var s = S.IDLE
    private var downT = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var moved = false
    private var tapDrag = false
    private var lastTapUpT = NEVER

    private var midStartX = 0f
    private var midStartY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var accX = 0f
    private var accY = 0f
    private var moved2 = false

    /** Place the cursor (e.g. after a direct touch on the picture). */
    fun jumpTo(x: Float, y: Float) {
        nx = x.coerceIn(0f, 1f)
        ny = y.coerceIn(0f, 1f)
    }

    /** Press / release a button at the cursor (used by the on-screen Left / Right click buttons). */
    fun button(name: String, down: Boolean) = out.button(name, down, nx, ny)

    // ------------------------------------------------------------------------------ one finger
    fun down(t: Long, x: Float, y: Float) {
        if (s != S.IDLE) cancel()
        s = S.ONE
        downT = t
        startX = x; startY = y; lastX = x; lastY = y; lastT = t
        moved = false
        tapDrag = lastTapUpT != NEVER && t - lastTapUpT <= cfg.doubleTapMs
    }

    fun move1(t: Long, x: Float, y: Float) {
        if (s != S.ONE) return
        if (!moved) {
            if (hypot(x - startX, y - startY) <= cfg.slopPx) return     // still a tap candidate
            moved = true
            if (tapDrag) {                                              // tap-and-a-half: hold the button
                out.button("left", true, nx, ny)
                dragging = true
            }
        }
        val dx = x - lastX
        val dy = y - lastY
        val dt = max(1L, t - lastT).toFloat()
        val speed = hypot(dx, dy) / dt
        val k = cfg.gain * (1f + min(cfg.accelMax, cfg.accel * speed))
        nx = (nx + dx * k / max(1f, areaW)).coerceIn(0f, 1f)
        ny = (ny + dy * k / max(1f, areaH)).coerceIn(0f, 1f)
        lastX = x; lastY = y; lastT = t
        out.move(nx, ny)
    }

    /** The last finger lifted. */
    fun up(t: Long) {
        if (s == S.ONE && !moved && t - downT <= cfg.tapMs) {
            out.button("left", true, nx, ny)
            out.button("left", false, nx, ny)
            lastTapUpT = if (tapDrag) NEVER else t       // a 2nd quick tap completes a double click
        } else {
            lastTapUpT = NEVER
        }
        if (dragging) {                                    // whatever state we are in: never leave it held
            out.button("left", false, nx, ny)
            dragging = false
        }
        s = S.IDLE
    }

    // ----------------------------------------------------------------------------- two fingers
    fun secondDown(t: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (s != S.ONE || dragging) {          // while dragging, extra fingers are ignored
            if (s != S.IDLE) s = S.IGNORE
            return
        }
        s = S.TWO
        lastTapUpT = NEVER
        midStartX = (x0 + x1) / 2f; midStartY = (y0 + y1) / 2f
        lastMidX = midStartX; lastMidY = midStartY
        accX = 0f; accY = 0f
        moved2 = moved                          // a finger that already travelled is not a tap
    }

    fun move2(t: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (s != S.TWO) return
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        if (!moved2) {
            if (hypot(mx - midStartX, my - midStartY) <= cfg.slopPx * 1.2f) return
            moved2 = true
            lastMidX = mx; lastMidY = my        // start counting from here: no jump when scrolling begins
            return
        }
        accX += mx - lastMidX
        accY += my - lastMidY
        lastMidX = mx; lastMidY = my
        val sx = (accX / cfg.scrollStepPx).toInt()
        val sy = (accY / cfg.scrollStepPx).toInt()
        if (sx != 0 || sy != 0) {
            accX -= sx * cfg.scrollStepPx
            accY -= sy * cfg.scrollStepPx
            // content follows the fingers: fingers down => wheel "up" (positive), same as the picture view
            out.scroll((-sx).coerceIn(-20, 20), sy.coerceIn(-20, 20))
        }
    }

    /** One of the two fingers lifted. */
    fun pointerUp(t: Long) {
        if (s == S.TWO && !moved2 && t - downT <= cfg.twoFingerTapMs) {
            out.button("right", true, nx, ny)
            out.button("right", false, nx, ny)
        }
        if (s != S.IDLE) s = S.IGNORE           // wait until every finger is up
    }

    /** Gesture aborted by the system (or the screen went away): never leave a button held. */
    fun cancel() {
        if (dragging) {
            out.button("left", false, nx, ny)
            dragging = false
        }
        lastTapUpT = NEVER
        s = S.IDLE
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
