package imicro.cryptic.terminal

import org.lwjgl.glfw.GLFW
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How many clicks a slot still wants, which is what settles a queued click.
 *
 * Que terms only lets go of a click the player made when the board itself stops
 * asking for that slot, and the amount it has stopped asking by is how many
 * clicks arrived. Getting that number wrong on a slot wanting several clicks is
 * what left a rubix pane being clicked back and forth: the click had plainly
 * landed, but the slot still wanted work, so the queue sent it a second time.
 */
class ClicksNeededTest {
	private val right = GLFW.GLFW_MOUSE_BUTTON_RIGHT
	private val left = GLFW.GLFW_MOUSE_BUTTON_MIDDLE

	private fun rubixNeeding(slot: Int, forwardSteps: Int): RubixHandler {
		val handler = RubixHandler()
		handler.solution.clear()
		repeat(forwardSteps) { handler.solution.add(slot) }
		return handler
	}

	@Test
	fun `a pane one step out wants one click`() {
		assertEquals(1, rubixNeeding(20, 1).clicksNeededFor(20))
	}

	@Test
	fun `a pane two steps out wants two`() {
		assertEquals(2, rubixNeeding(20, 2).clicksNeededFor(20))
	}

	@Test
	fun `a pane four steps out wants one, not four`() {
		// Four forward is one click backwards, and it is the click count that
		// says whether a queued click has been answered.
		assertEquals(1, rubixNeeding(20, 4).clicksNeededFor(20))
	}

	@Test
	fun `a pane three steps out wants two`() {
		assertEquals(2, rubixNeeding(20, 3).clicksNeededFor(20))
	}

	@Test
	fun `a finished pane wants none`() {
		assertEquals(0, rubixNeeding(20, 0).clicksNeededFor(20))
	}

	@Test
	fun `the count falls by one for each click that lands`() {
		// The sequence the queue reads to know its clicks arrived: three steps
		// out is two clicks, and each backwards click takes one off that.
		val handler = rubixNeeding(20, 3)
		assertEquals(2, handler.clicksNeededFor(20))

		handler.predict(20, right)
		assertEquals(1, handler.clicksNeededFor(20), "one click answered")

		handler.predict(20, right)
		assertEquals(0, handler.clicksNeededFor(20), "and the pane is done")
	}

	@Test
	fun `forward clicks count down the same way`() {
		val handler = rubixNeeding(20, 2)
		assertEquals(2, handler.clicksNeededFor(20))

		handler.predict(20, left)
		assertEquals(1, handler.clicksNeededFor(20))

		handler.predict(20, left)
		assertEquals(0, handler.clicksNeededFor(20))
	}

	@Test
	fun `panes wanting one click each are counted separately`() {
		val handler = PanesHandler()
		handler.solution.clear()
		handler.solution.addAll(listOf(11, 12, 13))

		assertEquals(1, handler.clicksNeededFor(11))
		assertEquals(1, handler.clicksNeededFor(12))
		assertEquals(0, handler.clicksNeededFor(20), "a slot nobody asked about wants nothing")

		handler.predict(11, left)
		assertEquals(0, handler.clicksNeededFor(11), "clicked, so it wants nothing more")
		assertEquals(1, handler.clicksNeededFor(12), "its neighbours are untouched")
	}
}
