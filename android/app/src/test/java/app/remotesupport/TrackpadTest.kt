package app.remotesupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadTest {
    private class Rec : Trackpad.Out {
        val log = mutableListOf<String>()
        var lastMove: Pair<Float, Float>? = null
        override fun move(nx: Float, ny: Float) { lastMove = nx to ny; log += "move" }
        override fun button(name: String, down: Boolean, nx: Float, ny: Float) { log += "$name${if (down) "Down" else "Up"}" }
        override fun scroll(dx: Int, dy: Int) { log += "scroll($dx,$dy)" }
    }

    /** Deterministic: no acceleration, 1000x500 px remote picture, 2x gain. */
    private fun pad(rec: Rec): Trackpad {
        val p = Trackpad(rec, Trackpad.Config(gain = 2f, accel = 0f))
        p.areaW = 1000f; p.areaH = 500f
        return p
    }

    @Test fun dragMovesThePointerRelatively() {
        val r = Rec(); val p = pad(r)
        p.down(0, 100f, 100f)
        p.move1(16, 150f, 100f)          // crosses the tap slop and moves 50px
        p.move1(32, 200f, 100f)          // another 50px
        p.up(48)
        // 100 finger px * gain 2 / 1000 px wide = +0.2 from the centre (0.5)
        assertEquals(0.7f, p.nx, 1e-4f)
        assertEquals(0.5f, p.ny, 1e-4f)
        assertFalse("a drag must not click", r.log.any { it.startsWith("left") })
    }

    @Test fun verticalUsesTheHeight() {
        val r = Rec(); val p = pad(r)
        p.down(0, 0f, 0f); p.move1(16, 0f, 50f); p.up(32)
        assertEquals(0.5f + 50f * 2f / 500f, p.ny, 1e-4f)
    }

    @Test fun cursorIsClampedToTheScreen() {
        val r = Rec(); val p = pad(r)
        p.down(0, 0f, 0f)
        var x = 0f; var t = 0L
        repeat(50) { x += 100f; t += 16; p.move1(t, x, 0f) }
        p.up(t + 16)
        assertEquals(1f, p.nx, 0f)
        p.down(t + 1000, 5000f, 0f)
        x = 5000f
        repeat(100) { x -= 100f; t += 16; p.move1(t + 1000, x, 0f) }
        p.up(t + 2000)
        assertEquals(0f, p.nx, 0f)
    }

    @Test fun smallJitterIsStillATap() {
        val r = Rec(); val p = pad(r)
        p.down(0, 100f, 100f); p.move1(20, 104f, 103f); p.up(80)
        assertEquals(listOf("leftDown", "leftUp"), r.log)
        assertEquals(0.5f, p.nx, 1e-6f)  // pointer did not wander
    }

    @Test fun longPressWithoutMovingDoesNotClick() {
        val r = Rec(); val p = pad(r)
        p.down(0, 10f, 10f); p.up(900)
        assertTrue(r.log.isEmpty())
    }

    @Test fun twoQuickTapsAreADoubleClick() {
        val r = Rec(); val p = pad(r)
        p.down(0, 10f, 10f); p.up(60)
        p.down(150, 10f, 10f); p.up(200)
        assertEquals(listOf("leftDown", "leftUp", "leftDown", "leftUp"), r.log)
    }

    @Test fun tapThenTouchAndMoveHoldsTheLeftButton() {
        val r = Rec(); val p = pad(r)
        p.down(0, 10f, 10f); p.up(60)                // tap #1 = click
        r.log.clear()
        p.down(150, 10f, 10f)                        // touch again within the double-tap window...
        p.move1(170, 60f, 10f)                       // ...and move: button goes down BEFORE the pointer moves
        p.move1(190, 110f, 10f)
        assertTrue(p.dragging)
        p.up(210)
        assertEquals(listOf("leftDown", "move", "move", "leftUp"), r.log)
    }

    @Test fun slowSecondTouchDoesNotDrag() {
        val r = Rec(); val p = pad(r)
        p.down(0, 10f, 10f); p.up(60)
        r.log.clear()
        p.down(1000, 10f, 10f); p.move1(1020, 60f, 10f); p.up(1040)
        assertFalse(r.log.contains("leftDown"))
        assertFalse(p.dragging)
    }

    @Test fun twoFingerTapIsARightClick() {
        val r = Rec(); val p = pad(r)
        p.down(0, 100f, 100f); p.secondDown(20, 100f, 100f, 200f, 100f)
        p.pointerUp(120); p.up(140)
        assertEquals(listOf("rightDown", "rightUp"), r.log)
    }

    @Test fun twoFingerDragScrollsAndDoesNotClick() {
        val r = Rec(); val p = pad(r)
        p.down(0, 100f, 100f); p.secondDown(10, 100f, 100f, 200f, 100f)
        var y = 100f; var t = 10L
        repeat(10) { y += 20f; t += 16; p.move2(t, 100f, y, 200f, y) }
        p.pointerUp(t + 16); p.up(t + 32)
        assertTrue(r.log.any { it.startsWith("scroll(") })
        assertFalse(r.log.any { it.startsWith("right") || it.startsWith("left") })
        val total = r.log.filter { it.startsWith("scroll(") }.sumOf { it.substringAfter(",").trimEnd(')').toInt() }
        assertTrue("fingers moving down must scroll the wheel up (positive), got $total", total > 0)
    }

    @Test fun secondFingerDuringDragIsIgnoredAndButtonIsReleased() {
        val r = Rec(); val p = pad(r)
        p.down(0, 10f, 10f); p.up(60)
        p.down(150, 10f, 10f); p.move1(170, 60f, 10f)
        p.secondDown(180, 60f, 10f, 160f, 10f)
        p.up(300)
        assertEquals("leftUp", r.log.last())
        assertFalse(p.dragging)
    }

    @Test fun cancelReleasesAHeldButton() {
        val r = Rec(); val p = pad(r)
        p.down(0, 10f, 10f); p.up(60)
        p.down(150, 10f, 10f); p.move1(170, 60f, 10f)
        p.cancel()
        assertEquals("leftUp", r.log.last())
        assertFalse(p.dragging)
    }

    @Test fun fastFlicksTravelFartherThanSlowMoves() {
        val slow = Rec(); val ps = Trackpad(slow, Trackpad.Config(gain = 1f)).also { it.areaW = 1000f; it.areaH = 500f }
        ps.down(0, 0f, 0f); ps.move1(1000, 100f, 0f)      // 100 px over a second
        val fast = Rec(); val pf = Trackpad(fast, Trackpad.Config(gain = 1f)).also { it.areaW = 1000f; it.areaH = 500f }
        pf.down(0, 0f, 0f); pf.move1(20, 100f, 0f)         // 100 px in 20 ms
        assertTrue(pf.nx > ps.nx)
    }

    @Test fun directTouchCanPlaceTheCursorAndButtonsUseIt() {
        val r = Rec(); val p = pad(r)
        p.jumpTo(0.25f, 0.75f)
        p.button("left", true); p.button("left", false)
        assertEquals(0.25f, p.nx, 0f); assertEquals(0.75f, p.ny, 0f)
        assertEquals(listOf("leftDown", "leftUp"), r.log)
    }
}
