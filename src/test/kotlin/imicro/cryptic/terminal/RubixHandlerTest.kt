package imicro.cryptic.terminal

import org.lwjgl.glfw.GLFW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rubix's click arithmetic.
 *
 * The solution holds one entry per forward step, so a pane needing four appears
 * four times and a right click — going the short way round — takes it the other
 * direction. Five steps is a whole loop and means the pane is finished.
 *
 * That last part is what went wrong for weeks: a right click added an entry and
 * nothing wrapped it, so a finished pane kept its five entries, stayed clickable
 * and eventually drew itself as wanting one more click. These are the cases that
 * would have caught it.
 */
class RubixHandlerTest {
	private val left = GLFW.GLFW_MOUSE_BUTTON_MIDDLE
	private val right = GLFW.GLFW_MOUSE_BUTTON_RIGHT

	/** A handler whose solution is set directly, so no chest is needed. */
	private fun handlerNeeding(slot: Int, steps: Int): RubixHandler {
		val handler = RubixHandler()
		handler.solution.clear()
		repeat(steps) { handler.solution.add(slot) }
		return handler
	}

	private fun stepsFor(handler: RubixHandler, slot: Int) = handler.solution.count { it == slot }

	@Test
	fun `a forward click takes one step off`() {
		val handler = handlerNeeding(20, 2)
		handler.predict(20, left)
		assertEquals(1, stepsFor(handler, 20))
	}

	@Test
	fun `the last forward click finishes the pane`() {
		val handler = handlerNeeding(20, 1)
		handler.predict(20, left)
		assertEquals(0, stepsFor(handler, 20))
		assertFalse(handler.canClick(20, left))
	}

	@Test
	fun `four steps is one click back, and it finishes the pane`() {
		val handler = handlerNeeding(20, 4)
		// Four forward is quicker the other way round, so the answer is a right
		// click — and one of them is the whole job.
		assertEquals(right, handler.preferredButton(20))

		handler.predict(20, right)

		assertEquals(0, stepsFor(handler, 20), "a whole loop is the same as standing still")
		assertFalse(handler.canClick(20, right), "a finished pane must not still accept clicks")
		assertEquals(null, handler.overlay(20), "and must not still be painted")
	}

	@Test
	fun `three steps is two clicks back`() {
		val handler = handlerNeeding(20, 3)
		assertEquals(right, handler.preferredButton(20))

		handler.predict(20, right)
		assertEquals(4, stepsFor(handler, 20))
		assertTrue(handler.canClick(20, right), "still one to go")

		handler.predict(20, right)
		assertEquals(0, stepsFor(handler, 20))
		assertFalse(handler.canClick(20, right))
	}

	@Test
	fun `a finished pane is never re-aimed`() {
		val handler = handlerNeeding(20, 4)
		handler.predict(20, right)
		assertEquals(null, handler.reaim(20, right), "nothing queued for it is worth sending")
	}

	@Test
	fun `going round twice cannot make a finished pane ask for more`() {
		// The exact shape of the report: a pane shown as -2, clicked past, and
		// then displaying 1 while the terminal was in fact complete.
		val handler = handlerNeeding(20, 3)
		assertEquals("-2", handler.overlay(20)?.text)

		handler.predict(20, right)
		assertEquals("-1", handler.overlay(20)?.text)

		handler.predict(20, right)
		assertEquals(null, handler.overlay(20))

		// A further click cannot be made, so it can never come back as "1".
		assertFalse(handler.canClick(20, right))
		assertFalse(handler.canClick(20, left))
	}

	@Test
	fun `panes are counted apart`() {
		val handler = handlerNeeding(20, 4)
		repeat(2) { handler.solution.add(21) }

		handler.predict(20, right)

		assertEquals(0, stepsFor(handler, 20))
		assertEquals(2, stepsFor(handler, 21), "its neighbour is untouched")
	}
}
